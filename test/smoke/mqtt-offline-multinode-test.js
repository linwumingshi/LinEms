/**
 * 多节点离线恢复验证：重连后订阅恢复 + 离线消息补发
 *
 * 验证两个持久会话承诺：
 *  1. 订阅恢复：设备 broker-1 建持久会话订阅 down/# → 断开 → 重连 broker-3，
 *     不重新 SUBSCRIBE，直接向订阅主题发消息 → 设备应能收到（订阅从 Redis 恢复）
 *  2. 离线补发：设备离线期间下发的消息进 Redis 离线队列 → 重连后逐条补发
 *
 * 运行：node test/smoke/mqtt-offline-multinode-test.js
 * 依赖：mysql2、mqtt、kafkajs、ioredis
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');
const { Kafka } = require('kafkajs');
const Redis = require('ioredis');

const ROOT = path.resolve(__dirname, '../..');

function loadEnv(file) {
  const env = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*export\s+([A-Z_]+)=(.*)$/);
    if (m) env[m[1]] = m[2].trim().replace(/^["']|["']$/g, '');
  }
  return env;
}

function sign(secret, clientId, timestamp, nonce) {
  return crypto.createHmac('sha256', secret).update(`${clientId}&${timestamp}&${nonce}`).digest('hex');
}

function connect(port, clientId, secret, clean) {
  const timestamp = Date.now().toString();
  const nonce = crypto.randomBytes(8).toString('hex');
  const username = `${clientId}&${timestamp}&${nonce}`;
  const password = sign(secret, clientId, timestamp, nonce);
  return mqtt.connect(`mqtt://127.0.0.1:${port}`, {
    clientId, username, password, clean,
    connectTimeout: 5000, reconnectPeriod: 0, keepalive: 30,
  });
}

function waitConnack(client) {
  return new Promise((resolve, reject) => {
    client.once('connect', (connack) => resolve({ ok: true, sessionPresent: connack.sessionPresent === true }));
    client.once('error', reject);
    setTimeout(() => reject(new Error('CONNECT 超时')), 8000);
  });
}

/** 通过 Kafka 向目标节点下行 topic 发指令（模拟 command 服务） */
async function sendDownlink(kafkaProducer, targetNode, clientId, topic, cmd, seq) {
  const envelope = Buffer.from(JSON.stringify({
    type: 'DOWNLINK',
    topic,
    payload: Buffer.from(JSON.stringify({ cmd, seq })).toString('base64'),
    sourceNode: 'command-test',
    ts: Date.now(),
  }));
  await kafkaProducer.send({
    topic: `mqtt.down.${targetNode}`,
    messages: [{ key: clientId, value: envelope }],
  });
}

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const redis = new Redis({ host: '127.0.0.1', port: 6379 });
  const kafka = new Kafka({ clientId: 'offline-multinode-test', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();

  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  const dev = devices[0];
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  console.log(`[设备] ${clientId} 订阅 ${downTopic}`);

  const results = [];

  // ========== 场景1：broker-1 建持久会话 + 订阅 ==========
  console.log('\n=== 场景1：broker-1 建立持久会话并订阅 ===');
  const c1 = connect(18831, clientId, dev.device_secret, false);
  await waitConnack(c1);
  await new Promise((res, rej) => {
    c1.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
  });
  await new Promise((r) => setTimeout(r, 800));
  const owner1 = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  连接 broker-1，订阅 down/#，锁 owner=${owner1}`);
  results.push({ step: 'subscribe-on-broker1', ok: owner1 === 'broker-1' });

  // ========== 场景2：断开（离线）后，向 broker-1 方向下发 3 条消息 ==========
  console.log('\n=== 场景2：设备离线，向原节点下发 3 条消息（应入离线队列） ===');
  await new Promise((r) => { c1.end(true, r); }); // 优雅断开
  await new Promise((r) => setTimeout(r, 800));
  // 等连接锁释放（broker 侧 releaseConnLockIfOwner）
  for (let i = 0; i < 10; i++) {
    const owner = await redis.get(`mqtt:conn:${clientId}`);
    if (!owner) break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const ownerAfterDisc = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  断开后锁 owner=${ownerAfterDisc || 'null（已释放）'}`);

  for (let i = 1; i <= 3; i++) {
    await sendDownlink(producer, 'broker-1', clientId, downTopic, 'charge', i);
    await new Promise((r) => setTimeout(r, 200));
  }
  console.log('  已向 mqtt.down.broker-1 发 3 条指令（设备离线）');

  // 检查离线队列（Redis key：离线队列由 BrokerKeys 管理，查一下实际 key）
  // 队列 key 通常是 {topic}:offline 或按 session，这里先探测
  const keys = await redis.keys('*sim-dev-000001*');
  console.log(`  Redis 相关 key: ${JSON.stringify(keys)}`);

  // ========== 场景3：重连 broker-3，验证订阅恢复 + 离线补发 ==========
  console.log('\n=== 场景3：重连 broker-3，验证订阅恢复 + 离线补发 ===');
  const c3 = connect(18833, clientId, dev.device_secret, false);
  const r3 = await waitConnack(c3);
  console.log(`  重连 broker-3 → sessionPresent=${r3.sessionPresent}`);

  // 不重新 subscribe，直接等待消息（验证订阅自动恢复 + 离线补发）
  const received = [];
  await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(), 10000);
    c3.on('message', (t, p) => {
      received.push({ topic: t, payload: p.toString() });
      if (received.length >= 3) { clearTimeout(timer); resolve(); }
    });
  });

  console.log(`  收到 ${received.length}/3 条离线消息:`);
  for (const m of received) console.log(`    ${m.topic} → ${m.payload}`);
  results.push({ step: 'subscription-restored-no-resubscribe', ok: received.length === 3 });
  results.push({ step: 'offline-redeliver-count', ok: received.length === 3 });

  // ========== 场景4：订阅仍生效——再发 1 条新消息，设备在线应直接收到 ==========
  console.log('\n=== 场景4：在线状态订阅仍生效（新消息直达） ===');
  const before = received.length;
  await sendDownlink(producer, 'broker-3', clientId, downTopic, 'discharge', 99);
  const gotNew = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), 8000);
    c3.on('message', (t, p) => { clearTimeout(timer); resolve({ topic: t, payload: p.toString() }); });
  });
  console.log(`  在线新消息: ${gotNew ? JSON.stringify(gotNew) : '无（超时）'}`);
  results.push({ step: 'online-subscription-alive', ok: gotNew !== null });

  await producer.disconnect();
  await conn.end();
  await redis.quit();
  await new Promise((r) => { c3.end(true, r); });

  console.log('\n=== 汇总 ===');
  const failed = results.filter((r) => !r.ok);
  for (const r of results) console.log(`  ${r.ok ? '✓' : '✗'} ${r.step}`);
  console.log(`\n结果: ${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
