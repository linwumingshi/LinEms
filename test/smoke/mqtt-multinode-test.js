/**
 * 多节点 Broker 集群验证（3 节点）
 *
 * 流程：
 *  1. 从 MySQL 读取已激活设备 + 凭据
 *  2. 同一台设备（clientId 唯一）依次连 broker-1/2/3 —— 验证跨节点会话跟随与连接锁 owner
 *  3. 每台连上后检查连接锁 owner（Redis mqtt:conn:{key}）是否指向对应节点
 *  4. 持久会话验证：cleanSession=false 连接 broker-1 订阅 → 断开 → 重连 broker-2 应 sessionPresent=1
 *  5. 下行定向验证：通过 Kafka 向设备所在节点发 mqtt.down.{nodeId}，设备应收到
 *
 * 运行：node test/smoke/mqtt-multinode-test.js
 * 依赖：mysql2、mqtt、kafkajs（受管 node 工作区）
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');
const { Kafka } = require('kafkajs');
const Redis = require('ioredis');

const ROOT = path.resolve(__dirname, '../..');

const BROKERS = [
  { node: 'broker-1', mqtt: 18831, mgmt: 8082 },
  { node: 'broker-2', mqtt: 18832, mgmt: 8083 },
  { node: 'broker-3', mqtt: 18833, mgmt: 8084 },
];

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

async function getDevice(conn) {
  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  if (devices.length === 0) throw new Error('无已激活设备');
  return devices[0];
}

/** 构造带认证的连接（MQTT 3.1.1，clientId={pk}_{dn}） */
function connect(port, clientId, secret, clean) {
  const timestamp = Date.now().toString();
  const nonce = crypto.randomBytes(8).toString('hex');
  const username = `${clientId}&${timestamp}&${nonce}`;
  const password = sign(secret, clientId, timestamp, nonce);
  return mqtt.connect(`mqtt://127.0.0.1:${port}`, {
    clientId,
    username,
    password,
    clean,
    connectTimeout: 5000,
    reconnectPeriod: 0,
  });
}

function waitConnack(client, expectedClean) {
  return new Promise((resolve, reject) => {
    client.once('connect', (connack) => {
      const sp = connack.sessionPresent === true;
      resolve({ ok: true, sessionPresent: sp });
    });
    client.once('error', (e) => reject(e));
    setTimeout(() => reject(new Error('CONNECT 超时')), 8000);
  });
}

async function checkConnLockOwner(redis, deviceKey) {
  const val = await redis.get(`mqtt:conn:${deviceKey}`);
  return val;
}

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const redis = new Redis({ host: '127.0.0.1', port: 6379 });
  const dev = await getDevice(conn);
  const clientId = `${dev.product_key}_${dev.device_name}`;
  console.log(`[设备] ${clientId} (secret=${dev.device_secret.slice(0, 6)}...)`);

  const results = [];

  // ========== 1. 同一设备依次连三个 broker，验证连接锁 owner ==========
  console.log('\n=== 场景1：同一设备跨节点连接，连接锁 owner 验证 ===');
  for (const b of BROKERS) {
    const c = connect(b.mqtt, clientId, dev.device_secret, true);
    try {
      const { sessionPresent } = await waitConnack(c, true);
      await new Promise((r) => setTimeout(r, 500)); // 等锁落 Redis
      const owner = await checkConnLockOwner(redis, clientId);
      const ok = owner === b.node;
      results.push({ step: `connect-${b.node}`, ok });
      console.log(`  连接 ${b.node}:188${b.mqtt % 10} → sessionPresent=${sessionPresent} 锁owner=${owner} ${ok ? '✓' : '✗ 应为' + b.node}`);
      // 每步之间断开，避免被踢
      await new Promise((r) => { c.end(true, r); });
      await new Promise((r) => setTimeout(r, 500));
    } catch (e) {
      results.push({ step: `connect-${b.node}`, ok: false });
      console.log(`  连接 ${b.node} 失败: ${e.message}`);
      c.end(true);
    }
  }

  // ========== 2. 持久会话跨节点恢复 ==========
  console.log('\n=== 场景2：持久会话跨节点恢复（sessionPresent） ===');
  {
    // 连 broker-1 建立持久会话并订阅
    const c1 = connect(BROKERS[0].mqtt, clientId, dev.device_secret, false);
    const r1 = await waitConnack(c1, false);
    await new Promise((res, rej) => {
      c1.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
    });
    await new Promise((r) => setTimeout(r, 500));
    await new Promise((r) => { c1.end(true, r); });
    // 重连 broker-2，应 sessionPresent=1
    const c2 = connect(BROKERS[1].mqtt, clientId, dev.device_secret, false);
    const r2 = await waitConnack(c2, false);
    const ok = r2.sessionPresent === true;
    results.push({ step: 'session-recover-cross-node', ok });
    console.log(`  broker-1 建会话订阅 → broker-2 重连 sessionPresent=${r2.sessionPresent} ${ok ? '✓' : '✗'}`);
    await new Promise((r) => { c2.end(true, r); });
  }

  // ========== 3. 跨节点下行定向 ==========
  console.log('\n=== 场景3：跨节点下行定向（mqtt.down.{nodeId}） ===');
  {
    // 设备连 broker-3，订阅下行
    const c3 = connect(BROKERS[2].mqtt, clientId, dev.device_secret, true);
    await waitConnack(c3, true);
    await new Promise((res, rej) => {
      c3.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
    });
    await new Promise((r) => setTimeout(r, 500));
    const owner = await checkConnLockOwner(redis, clientId);
    console.log(`  设备当前在 broker-3，锁 owner=${owner}`);

    // 通过 Kafka 往 mqtt.down.broker-3 发一条指令
    const kafka = new Kafka({
      clientId: 'multinode-test',
      brokers: ['127.0.0.1:9092'],
    });
    const producer = kafka.producer();
    await producer.connect();
    const envelope = Buffer.from(JSON.stringify({
      type: 'DOWNLINK',
      topic: `${dev.product_key}/${dev.device_name}/down/command`,
      payload: Buffer.from(JSON.stringify({ cmd: 'charge', power: 50 })).toString('base64'),
      sourceNode: 'broker-1',
      ts: Date.now(),
    }));
    // 用最简信封：直接发 topic=设备下行topic 的消息，让 broker 定向消费后投递
    await producer.send({
      topic: `mqtt.down.${BROKERS[2].node}`,
      messages: [{ key: clientId, value: envelope }],
    });
    console.log(`  已发送到 mqtt.down.${BROKERS[2].node}`);

    // 设备等消息
    const got = await new Promise((resolve) => {
      const timer = setTimeout(() => resolve(null), 8000);
      c3.on('message', (t, p) => {
        clearTimeout(timer);
        resolve({ topic: t, payload: p.toString() });
      });
    });
    const ok = got !== null;
    results.push({ step: 'downlink-directed', ok });
    console.log(`  设备收到: ${got ? JSON.stringify(got) : '无（超时）'} ${ok ? '✓' : '✗'}`);
    await producer.disconnect();
    await new Promise((r) => { c3.end(true, r); });
  }

  await conn.end();
  await redis.quit();

  // ========== 汇总 ==========
  console.log('\n=== 汇总 ===');
  const failed = results.filter((r) => !r.ok);
  for (const r of results) console.log(`  ${r.ok ? '✓' : '✗'} ${r.step}`);
  console.log(`\n结果: ${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
