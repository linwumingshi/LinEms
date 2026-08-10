/**
 * v5 属性协商验证：CONNACK 能力声明（Maximum QoS / Retain Available）+
 * Maximum Packet Size 客户端声明被服务端遵守（超限下行不发送）。
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
function encodeEnvelope(sourceNode, topic, payload, qos, retain) {
  const buf = Buffer.alloc(4096);
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
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const a = auth(dev.device_secret, clientId);
  const downFilter = `${dev.product_key}/${dev.device_name}/down/#`;
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  console.log(`[props] 设备 clientId=${clientId}`);

  // 1. v5 连接，声明 Maximum Packet Size=128
  const received = [];
  const client = mqtt.connect({ host: '127.0.0.1', port: MQTT_PORT, protocolVersion: 5,
    clientId, username: a.username, password: a.password, clean: true, keepalive: 30,
    reconnectPeriod: 0, properties: { maximumPacketSize: 128 } });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    client.on('connect', async (pkt) => {
      clearTimeout(t);
      const p = pkt.properties || {};
      console.log(`[props] CONNACK 能力声明 maximumQoS=${p.maximumQoS} retainAvailable=${p.retainAvailable}`);
      if (p.maximumQoS !== 2 || p.retainAvailable !== true) reject(new Error('CONNACK 能力声明缺失'));
      await client.subscribeAsync(downFilter);
      resolve();
    });
    client.on('message', (topic, payload) => { received.push(payload.length); console.log(`[props] 设备收到报文 ${payload.length}B`); });
    client.on('error', reject);
  });

  // 2. 下发一个超大下行（>128B）→ 应被 Maximum Packet Size 拦截，设备收不到
  const kafka = new Kafka({ clientId: 'smoke-props', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();
  const bigPayload = Buffer.from(JSON.stringify({ commandId: 'big-cmd', command: 'x', params: { padding: 'x'.repeat(300) } }));
  await producer.send({ topic: 'mqtt.down.broker-1', messages: [{ key: clientId, value: encodeEnvelope('access-1', downTopic, bigPayload, 1, false) }] });
  await new Promise((r) => setTimeout(r, 2500));
  console.log(`[props] 超大下行(est=${downTopic.length + bigPayload.length + 32}B) 设备收到数=${received.length}（预期 0，被拦截）`);

  // 3. 下发小报文（<128B）→ 应送达
  const smallPayload = Buffer.from(JSON.stringify({ commandId: 'small-cmd', command: 'y' }));
  await producer.send({ topic: 'mqtt.down.broker-1', messages: [{ key: clientId, value: encodeEnvelope('access-1', downTopic, smallPayload, 1, false) }] });
  await new Promise((r) => setTimeout(r, 2500));
  await producer.disconnect();
  console.log(`[props] 小报文送达数=${received.length}（预期 1）`);
  if (received.length !== 1) throw new Error(`小报文未送达（收到 ${received.length}）`);

  await client.endAsync();
  console.log('[props] v5 属性协商验证通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[props] 失败:', e.message); process.exit(1); });
