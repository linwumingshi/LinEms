/**
 * 订阅恢复 + 命令可达完整闭环验证
 *
 * 链路（贴近真实运维）：
 *  1. 设备连 broker-1，订阅 down/# → 平台发命令 → 设备收到（在线基线）
 *  2. 设备断开（中断）
 *  3. 设备重连 broker-2（跨节点接管），不重新 SUBSCRIBE → sessionPresent 应 =true
 *  4. 平台再发命令 → 设备应收到（证明重连后订阅仍生效、命令可达）
 *
 * 运行：node test/smoke/mqtt-subscribe-restore-test.js
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

/** 通过 Kafka 向指定节点下行 topic 发命令，返回 Promise，设备收到后 resolve */
function sendAndWait(producer, targetNode, clientId, topic, cmd, seq, client, timeoutMs = 8000) {
  return new Promise(async (resolve) => {
    const envelope = Buffer.from(JSON.stringify({
      type: 'DOWNLINK',
      topic,
      payload: Buffer.from(JSON.stringify({ cmd, seq })).toString('base64'),
      sourceNode: 'subscribe-restore-test',
      ts: Date.now(),
    }));
    const timer = setTimeout(() => resolve({ ok: false, reason: 'timeout' }), timeoutMs);
    const onMsg = (t, p) => {
      clearTimeout(timer);
      client.removeListener('message', onMsg);
      resolve({ ok: true, topic: t, payload: p.toString() });
    };
    client.on('message', onMsg);
    try {
      await producer.send({ topic: `mqtt.down.${targetNode}`, messages: [{ key: clientId, value: envelope }] });
    } catch (e) {
      clearTimeout(timer);
      client.removeListener('message', onMsg);
      resolve({ ok: false, reason: 'kafka-send-failed: ' + e.message });
    }
  });
}

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const redis = new Redis({ host: '127.0.0.1', port: 6379 });
  const kafka = new Kafka({ clientId: 'subscribe-restore-test', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();

  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  const dev = devices[0];
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  console.log(`[设备] ${clientId}`);

  const results = [];

  // ========== 场景1：在线基线——broker-1 订阅 + 收命令 ==========
  console.log('\n=== 场景1：broker-1 订阅 down/#，在线收命令（基线） ===');
  const c1 = connect(18831, clientId, dev.device_secret, false);
  await waitConnack(c1);
  await new Promise((res, rej) => {
    c1.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
  });
  await new Promise((r) => setTimeout(r, 500));
  const r1 = await sendAndWait(producer, 'broker-1', clientId, downTopic, 'charge', 1, c1);
  console.log(`  收到命令: ${r1.ok ? r1.payload : r1.reason}`);
  results.push({ step: 'online-receive-command', ok: r1.ok });
  results.push({ step: 'conn-lock-broker1', ok: (await redis.get(`mqtt:conn:${clientId}`)) === 'broker-1' });

  // ========== 场景2：设备中断（非优雅断开，模拟掉线） ==========
  console.log('\n=== 场景2：设备中断 ===');
  await new Promise((r) => { c1.end(true, r); }); // 主动断开
  await new Promise((r) => setTimeout(r, 800));
  // 等连接锁释放
  for (let i = 0; i < 10; i++) {
    if (!(await redis.get(`mqtt:conn:${clientId}`))) break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const owner = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  已断开，锁 owner=${owner || 'null（已释放）'}`);
  results.push({ step: 'disconnect-release-lock', ok: !owner });

  // ========== 场景3：重连 broker-2（跨节点），不重新订阅 ==========
  console.log('\n=== 场景3：重连 broker-2（跨节点接管），不重新 SUBSCRIBE ===');
  const c2 = connect(18832, clientId, dev.device_secret, false);
  const r3 = await waitConnack(c2);
  await new Promise((r) => setTimeout(r, 800));
  const owner2 = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  sessionPresent=${r3.sessionPresent} 锁 owner=${owner2}`);
  results.push({ step: 'reconnect-session-present', ok: r3.sessionPresent === true });
  results.push({ step: 'reconnect-lock-new-node', ok: owner2 === 'broker-2' });

  // ========== 场景4：重连后平台再发命令 → 应收到（订阅恢复 + 命令可达） ==========
  console.log('\n=== 场景4：重连后发新命令（验证订阅恢复 + 命令可达） ===');
  const r4 = await sendAndWait(producer, 'broker-2', clientId, downTopic, 'discharge', 2, c2);
  console.log(`  收到命令: ${r4.ok ? r4.payload : r4.reason}`);
  results.push({ step: 'post-reconnect-receive-command', ok: r4.ok });

  // ========== 场景5：再发第 2 条，确认持续可达 ==========
  console.log('\n=== 场景5：重连后第 2 条命令（持续可达） ===');
  const r5 = await sendAndWait(producer, 'broker-2', clientId, downTopic, 'standby', 3, c2);
  console.log(`  收到命令: ${r5.ok ? r5.payload : r5.reason}`);
  results.push({ step: 'post-reconnect-second-command', ok: r5.ok });

  await producer.disconnect();
  await conn.end();
  await redis.quit();
  await new Promise((r) => { c2.end(true, r); });

  console.log('\n=== 汇总 ===');
  const failed = results.filter((r) => !r.ok);
  for (const r of results) console.log(`  ${r.ok ? '✓' : '✗'} ${r.step}`);
  console.log(`\n结果: ${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
