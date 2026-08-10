/**
 * 离线队列逐条补发验证（可靠性）：设备离线期间的下行指令，重连后应逐条补发到达。
 *
 * 流程：
 *  1. 设备 v4 连接（持久会话 cleanSession=false）订阅 down/#
 *  2. 非优雅断开（离线，连接锁释放）
 *  3. 模拟 access 写 mqtt.down.broker-1（owner 缺失 → 持久会话入 Redis 离线队列）
 *  4. 设备重连 → deliverOfflineQueue 逐条补发 → 应收到离线指令
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');
const { Kafka } = require('kafkajs');

const ROOT = path.resolve(__dirname, '../..');
const MQTT_PORT = 18831;

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
function auth(secret, clientId) {
  const ts = Date.now();
  const nonce = crypto.randomBytes(8).toString('hex');
  return { username: `${clientId}&${ts}&${nonce}`, password: sign(secret, clientId, String(ts), nonce) };
}
function connect(secret, clientId, clean) {
  const a = auth(secret, clientId);
  return mqtt.connect({ host: '127.0.0.1', port: MQTT_PORT, protocolVersion: 4,
    clientId, username: a.username, password: a.password, clean, keepalive: 30, reconnectPeriod: 0 });
}
function encodeEnvelope(sourceNode, topic, payload, qos, retain) {
  const buf = Buffer.alloc(1024);
  let pos = 0;
  const wb = (b) => { buf[pos++] = b; };
  const wi = (v) => { buf.writeInt32BE(v, pos); pos += 4; };
  const ws = (s) => { const b = Buffer.from(s || '', 'utf8'); wi(b.length); b.copy(buf, pos); pos += b.length; };
  const wl = (v) => { buf.writeBigInt64BE(BigInt(v), pos); pos += 8; };
  wb(0xE9); wb(0x01); wb(1); wb('P'.charCodeAt(0));
  ws(sourceNode); ws(topic);
  wi(payload.length); payload.copy(buf, pos); pos += payload.length;
  wb(qos); wb(retain ? 1 : 0);
  wi(0xFFFF);
  wl(Date.now());
  return buf.subarray(0, pos);
}

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  await conn.end();
  if (!devices.length) throw new Error('无可用设备');
  const dev = devices[0];
  const secret = dev.device_secret;
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const downFilter = `${dev.product_key}/${dev.device_name}/down/#`;
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  console.log(`[offline] 设备 clientId=${clientId}`);

  // 1. 设备连接并订阅
  const c1 = connect(secret, clientId, false);
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    c1.on('connect', async () => { await c1.subscribeAsync(downFilter); clearTimeout(t); resolve(); });
    c1.on('error', reject);
  });
  console.log('[offline] 设备已连接并订阅 down/#');

  // 2. 非优雅断开（离线）
  await c1.endAsync(true);
  console.log('[offline] 设备已离线');

  // 3. 等待连接锁释放 + 写 2 条离线下行（owner 缺失 → 入 Redis 离线队列）
  await new Promise((r) => setTimeout(r, 3000));
  const kafka = new Kafka({ clientId: 'smoke-offline', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();
  for (let i = 0; i < 2; i++) {
    const payload = Buffer.from(JSON.stringify({ commandId: `offline-cmd-${i}`, command: 'setVoltage', params: { voltage: 380 + i }, ts: Date.now() }));
    const envelope = encodeEnvelope('access-1', downTopic, payload, 1, false);
    await producer.send({ topic: 'mqtt.down.broker-1', messages: [{ key: clientId, value: envelope }] });
    console.log(`[offline] 已写入离线指令 ${i}`);
  }
  await producer.disconnect();
  await new Promise((r) => setTimeout(r, 3000)); // 等 broker 消费入队

  // 4. 设备重连 → 应收到 2 条离线补发
  const received = [];
  const c2 = connect(secret, clientId, false);
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('重连/补发超时')), 20000);
    c2.on('connect', () => { console.log('[offline] 设备重连成功'); });
    c2.on('message', (topic, payload) => {
      received.push(payload.toString());
      console.log(`[offline] 收到离线补发 ${payload.toString()}`);
      if (received.length >= 2) { clearTimeout(t); resolve(); }
    });
    c2.on('error', reject);
  });
  await c2.endAsync();

  if (received.length !== 2) throw new Error(`离线补发不完整，收到 ${received.length}/2`);
  console.log('[offline] 离线队列逐条补发验证通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[offline] 失败:', e.message); process.exit(1); });
