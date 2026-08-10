/**
 * 下行定向投递验证（阶段 2）：access 侧发布 CommandDown → mqtt.down.broker-1 → 设备收到。
 *
 * 依赖 kafkajs（若未安装则提示）；NODE_PATH 指向受管 node 工作区。
 * 运行：node test/smoke/mqtt-downlink.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');

const ROOT = path.resolve(__dirname, '../..');
const BROKER = '127.0.0.1';
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

// 复刻 RouterEnvelopeCodec 二进制信封（与 Java 端字节级兼容）
function encodeEnvelope(sourceNode, topic, payload, qos, retain) {
  const buf = Buffer.alloc(1024);
  let pos = 0;
  const wb = (b) => { buf[pos++] = b; };
  const wi = (v) => { buf.writeInt32BE(v, pos); pos += 4; };
  const ws = (s) => { const b = Buffer.from(s || '', 'utf8'); wi(b.length); b.copy(buf, pos); pos += b.length; };
  const wl = (v) => { buf.writeBigInt64BE(BigInt(v), pos); pos += 8; };
  wb(0xE9); wb(0x01); wb(1); wb('P'.charCodeAt(0)); // magic + version + type
  ws(sourceNode); ws(topic);
  wi(payload.length); payload.copy(buf, pos); pos += payload.length;
  wb(qos); wb(retain ? 1 : 0);
  wi(0xFFFF); // deviceKey=null
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
  const ts = Date.now();
  const nonce = crypto.randomBytes(8).toString('hex');
  const username = `${clientId}&${ts}&${nonce}`;
  const password = sign(secret, clientId, String(ts), nonce);
  const downFilter = `${dev.product_key}/${dev.device_name}/down/#`;

  // 1. 设备连接并订阅 down/#
  const client = mqtt.connect({ host: BROKER, port: MQTT_PORT, protocolVersion: 4,
    clientId, username, password, clean: true, keepalive: 30, reconnectPeriod: 0 });

  const downMsg = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('下行消息 15s 未到达')), 15000);
    client.on('message', (topic, payload) => {
      clearTimeout(timer);
      console.log(`[downlink] 设备收到下行 topic=${topic} payload=${payload.toString()}`);
      resolve(topic);
    });
    client.on('error', reject);
  });

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('连接超时')), 15000);
    client.on('connect', async () => {
      await client.subscribeAsync(downFilter);
      clearTimeout(timer);
      console.log(`[downlink] 设备已连接并订阅 ${downFilter}`);
      resolve();
    });
  });

  // 2. 直连 Kafka 写 mqtt.down.broker-1（模拟 access 定向投递；真实环境由 EventPublisher 完成）
  const kafka = require('kafkajs');
  const k = new kafka.Kafka({ clientId: 'smoke-downlink', brokers: ['127.0.0.1:9092'] });
  const producer = k.producer();
  await producer.connect();
  const downTopic = `${dev.product_key}/${dev.device_name}/down/command`;
  const payload = Buffer.from(JSON.stringify({
    commandId: 'smoke-cmd-1', command: 'setVoltage', params: { voltage: 400 }, ts: Date.now(),
  }));
  const envelope = encodeEnvelope('access-1', downTopic, payload, 1, false);
  await producer.send({ topic: 'mqtt.down.broker-1', messages: [{ key: clientId, value: envelope }] });
  console.log(`[downlink] 已写入 mqtt.down.broker-1 key=${clientId} envelope=${envelope.length}B`);
  await producer.disconnect();

  const received = await downMsg;
  if (received !== downTopic) throw new Error(`下行 topic 不匹配: ${received}`);
  await client.endAsync();
  console.log('[downlink] 下行定向投递验证通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[downlink] 失败:', e.message); process.exit(1); });
