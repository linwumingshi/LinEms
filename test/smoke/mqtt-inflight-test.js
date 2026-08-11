/**
 * QoS1 inflight 续传验证：消息发出未确认 → 断线 → 重连后重发（不丢不重）
 *
 * 机制（SessionStore.java）：
 *  - broker 发 QoS1 PUBLISH 时把消息写入 Redis Hash `mqtt:inflight:{deviceKey}`（key=packetId）
 *  - 收到设备 PUBACK 后 removeInflight 删除
 *  - 重连恢复时 loadInflight 取出未确认消息重发（resendInflight）
 *
 * 验证方法：设备收到 PUBLISH 后故意不回 PUBACK（用 mqtt.js handleMessage 钩子吞掉），
 * 断线 → 重连 → broker 应重发该消息。
 *
 * 运行：node test/smoke/mqtt-inflight-test.js
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

/** 构造带认证的连接。noPuback=true 时用 handleMessage 钩子吞掉 PUBLISH 不回 ACK */
function connect(port, clientId, secret, clean, noPuback) {
  const timestamp = Date.now().toString();
  const nonce = crypto.randomBytes(8).toString('hex');
  const username = `${clientId}&${timestamp}&${nonce}`;
  const password = sign(secret, clientId, timestamp, nonce);
  const opts = {
    clientId, username, password, clean,
    connectTimeout: 5000, reconnectPeriod: 0, keepalive: 30,
  };
  if (noPuback) {
    // mqtt.js 收到 QoS1/2 PUBLISH 默认自动回 ACK；用 handleMessage 钩子接管后不调用默认回调，
    // 从而吞掉 PUBLISH、不发送 PUBACK（但需要手动 emit message 供上层观察）
    opts.handleMessage = (packet, cb) => {
      if (packet.cmd === 'publish') {
        client.emit('message', packet.topic, packet.payload, packet);
      }
      // 不调用 cb → 不发 PUBACK
    };
  }
  const client = mqtt.connect(`mqtt://127.0.0.1:${port}`, opts);
  return client;
}

function waitConnack(client) {
  return new Promise((resolve, reject) => {
    client.once('connect', (connack) => resolve({ ok: true, sessionPresent: connack.sessionPresent === true }));
    client.once('error', reject);
    setTimeout(() => reject(new Error('CONNECT 超时')), 8000);
  });
}

/** 二进制信封（RouterEnvelopeCodec 格式：magic 0xE9 0x01 + type 'P' + sourceNode + topic + payload + qos + retain + packetId + ts） */
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
  const redis = new Redis({ host: '127.0.0.1', port: 6379 });
  const kafka = new Kafka({ clientId: 'inflight-test', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();

  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  const dev = devices[0];
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  const inflightKey = `mqtt:inflight:${clientId}`;
  console.log(`[设备] ${clientId}`);

  const results = [];

  // ========== 场景1：连接（noPuback 模式），建立持久会话 + 订阅 ==========
  console.log('\n=== 场景1：连接（吞 ACK 模式）+ 订阅 down/# ===');
  const c1 = connect(18831, clientId, dev.device_secret, false, true);
  await waitConnack(c1);
  // 用底层原始订阅避免 handleMessage 钩子吞 SUBACK 回调
  const rawSub = c1._client && c1._client.stream ? c1 : c1;
  await new Promise((resolve) => {
    const subReq = c1.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 });
    subReq.on('error', () => resolve()); // 钩子接管时回调可能报错，忽略
    setTimeout(() => resolve(), 1500); // 无论如何等订阅发出
  });
  console.log('  订阅请求已发出（钩子模式下 SUBACK 回调被吞，稍后从投递验证订阅生效）');
  await new Promise((r) => setTimeout(r, 1500));

  // ========== 场景2：平台发 QoS1 命令 → 设备收到但不回 PUBACK ==========
  console.log('\n=== 场景2：发命令（二进制信封 QoS1），设备收到但不回 PUBACK ===');
  const envelope1 = encodeEnvelope('inflight-test', downTopic,
      Buffer.from(JSON.stringify({ cmd: 'charge', seq: 'inflight-1' })), 1, false);
  await producer.send({ topic: 'mqtt.down.broker-1', messages: [{ key: clientId, value: envelope1 }] });

  // 等设备收到（但不回 ACK）
  const got1 = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), 8000);
    c1.on('message', (t, p) => { clearTimeout(timer); resolve({ topic: t, payload: p.toString() }); });
  });
  console.log(`  设备收到: ${got1 ? got1.payload : '无（超时）'}`);
  await new Promise((r) => setTimeout(r, 1500)); // 等 inflight 落 Redis
  const inflightAfterSend = await redis.hgetall(inflightKey);
  console.log(`  Redis inflight Hash: ${JSON.stringify(inflightAfterSend)}`);
  results.push({ step: 'received-no-puback', ok: got1 !== null });
  results.push({ step: 'inflight-saved', ok: Object.keys(inflightAfterSend).length >= 1 });

  // ========== 场景3：断线（不 ACK 直接断） ==========
  console.log('\n=== 场景3：未确认直接断线 ===');
  await new Promise((r) => { c1.end(true, r); });
  await new Promise((r) => setTimeout(r, 1000));
  const inflightAfterDisc = await redis.hgetall(inflightKey);
  console.log(`  断开后 inflight 仍在 Redis: ${JSON.stringify(inflightAfterDisc)}`);
  results.push({ step: 'inflight-persisted-after-disc', ok: Object.keys(inflightAfterDisc).length >= 1 });

  // ========== 场景4：重连（正常模式，会回 ACK）→ 应收到 inflight 重发 ==========
  console.log('\n=== 场景4：重连，应收到 inflight 重发 ===');
  const c2 = connect(18831, clientId, dev.device_secret, false, false); // 正常模式
  const r4 = await waitConnack(c2);
  console.log(`  重连 sessionPresent=${r4.sessionPresent}`);
  // 重连后主动订阅（确保收到重发）
  await new Promise((res, rej) => {
    c2.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
  });
  const got2 = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), 8000);
    c2.on('message', (t, p) => { clearTimeout(timer); resolve({ topic: t, payload: p.toString() }); });
  });
  console.log(`  重连后收到: ${got2 ? got2.payload : '无（超时）'}`);
  // 检查 inflight 是否已被消费（PUBACK 后应清空）
  await new Promise((r) => setTimeout(r, 1000));
  const inflightAfterAck = await redis.hgetall(inflightKey);
  console.log(`  确认后 inflight: ${JSON.stringify(inflightAfterAck)}`);
  results.push({ step: 'resend-on-reconnect', ok: got2 !== null && got2.payload.includes('inflight-1') });
  results.push({ step: 'inflight-cleared-after-ack', ok: Object.keys(inflightAfterAck).length === 0 });

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
