/**
 * MQTT 5.0 冒烟测试（P1-11：Session Expiry Interval / Receive Maximum / Will Delay 解析）
 *
 * 流程：
 *  1. 从 MySQL 读取一台已激活设备及其凭据
 *  2. MQTT 5.0 连接（cleanSession=false + sessionExpiryInterval=0）→ 校验 CONNACK
 *  3. SUBSCRIBE down/# → PUBLISH up/property QoS1 → PUBACK（Kafka 持久化后）
 *  4. 非优雅断开 → 重连：sessionExpiryInterval=0 的会话应已删除（sessionPresent=0）
 *  5. 不带 expiry 重连（默认保留 7 天）→ 再断开重连 → sessionPresent=1
 *
 * 运行：node test/smoke/mqtt-smoke-v5.js
 * 依赖：mysql2、mqtt（受管 node 工作区）
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');

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
function connect(secret, clientId, clean, props) {
  const a = auth(secret, clientId);
  return mqtt.connect({ host: '127.0.0.1', port: MQTT_PORT, protocolVersion: 5,
    clientId, username: a.username, password: a.password, clean, keepalive: 30,
    reconnectPeriod: 0, properties: props || {} });
}
function once(client, event, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { client.removeAllListeners(); reject(new Error(`${event} 超时`)); }, timeoutMs);
    client.once(event, (pkt) => { clearTimeout(timer); resolve(pkt); });
    client.once('error', (e) => { clearTimeout(timer); reject(e); });
  });
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
  const upTopic = `${dev.product_key}/${dev.device_name}/up/property`;
  const downFilter = `${dev.product_key}/${dev.device_name}/down/#`;
  console.log(`[smoke-v5] 设备 clientId=${clientId}`);

  // ---- 1. v5 连接：sessionExpiryInterval=0（断开即过期）----
  const c1 = connect(secret, clientId, false, { sessionExpiryInterval: 0 });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('v5 连接超时')), 15000);
    c1.on('connect', async (pkt) => {
      clearTimeout(t);
      console.log(`[smoke-v5] v5 CONNACK sessionPresent=${pkt.sessionPresent}（首连预期 0）`);
      try {
        await c1.subscribeAsync(downFilter, { qos: 1 });
        const t0 = Date.now();
        await c1.publishAsync(upTopic, JSON.stringify({ voltage: 380.1, ts: Date.now() }), { qos: 1 });
        console.log(`[smoke-v5] v5 QoS1 PUBACK 收到（Kafka 确认后），ack 时延 ${Date.now() - t0}ms`);
        resolve();
      } catch (e) { reject(e); }
    });
    c1.on('error', (e) => { clearTimeout(t); reject(e); });
  });
  await c1.endAsync(true); // 非优雅断开

  // ---- 2. 重连：expiry=0 会话应已删除 → sessionPresent=0 ----
  const c2 = connect(secret, clientId, false, { sessionExpiryInterval: 0 });
  const pkt2 = await once(c2, 'connect', 15000);
  console.log(`[smoke-v5] expiry=0 重连 sessionPresent=${pkt2.sessionPresent}（预期 0 = 会话已过期删除）`);
  if (pkt2.sessionPresent) throw new Error('sessionExpiryInterval=0 会话未被删除');
  await c2.endAsync(true);

  // ---- 3. 不带 expiry（默认保留）→ 断开重连 sessionPresent=1 ----
  const c3 = connect(secret, clientId, false, {});
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    c3.on('connect', () => { clearTimeout(t); resolve(); });
    c3.on('error', (e) => { clearTimeout(t); reject(e); });
  });
  await c3.endAsync(true); // 断开（持久会话保留）
  const c4 = connect(secret, clientId, false, {});
  const pkt4 = await once(c4, 'connect', 15000);
  console.log(`[smoke-v5] 默认持久会话重连 sessionPresent=${pkt4.sessionPresent}（预期 1）`);
  if (!pkt4.sessionPresent) throw new Error('默认持久会话未保留');
  await c4.endAsync();

  console.log('[smoke-v5] 全部通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[smoke-v5] 失败:', e.message); process.exit(1); });
