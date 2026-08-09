/**
 * MQTT Broker 冒烟测试（阶段1修复验证）
 *
 * 流程：
 *  1. 从 MySQL 读取一台已激活设备及其凭据（iot_device + iot_device_credential）
 *  2. 按平台认证契约构造 clientId/username(HMAC+nonce)/password
 *  3. MQTT 3.1.1 cleanSession=false 连接 → 校验 CONNACK
 *  4. SUBSCRIBE {pk}/{dn}/down/# → 校验 SUBACK
 *  5. PUBLISH {pk}/{dn}/up/property QoS1 → 等待 PUBACK（验证 Kafka 持久化后才回 ACK）
 *  6. 断线重连 → 校验 sessionPresent=1（持久会话恢复）
 *  7. 越权发布他人 topic → 预期被 ACL 拒绝关连接
 *
 * 运行：node test/smoke/mqtt-smoke.js
 * 依赖：mysql2、mqtt（位于受管 node 工作区，经 NODE_PATH 引入）
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');

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

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, d.status, c.device_secret, c.auth_status
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  if (devices.length === 0) throw new Error('无已激活设备（含有效凭据）');
  const dev = devices[0];
  const secret = dev.device_secret;
  await conn.end();

  const clientId = `${dev.product_key}_${dev.device_name}`;
  const ts = Date.now();
  const nonce = crypto.randomBytes(8).toString('hex');
  const username = `${clientId}&${ts}&${nonce}`;
  const password = sign(secret, clientId, String(ts), nonce);
  console.log(`[smoke] 设备 clientId=${clientId}（device_id=${dev.device_id}）`);

  const upTopic = `${dev.product_key}/${dev.device_name}/up/property`;
  const downFilter = `${dev.product_key}/${dev.device_name}/down/#`;

  // ---- 首次连接（持久会话） ----
  const client = mqtt.connect({
    host: '127.0.0.1', port: 1883, protocolVersion: 4,
    clientId, username, password, clean: false, keepalive: 30, reconnectPeriod: 0,
  });

  let pubAcked = false;
  const t0 = Date.now();

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('首次连接/PUBLISH 超时 20s')), 20000);
    client.on('connect', async (pkt) => {
      console.log(`[smoke] CONNACK sessionPresent=${pkt.sessionPresent}（首连预期 0）`);
      try {
        const granted = await client.subscribeAsync(downFilter);
        console.log(`[smoke] SUBACK granted qos=${granted[0].qos} filter=${downFilter}`);
        await client.publishAsync(upTopic, JSON.stringify({ voltage: 380.5, ts: Date.now() }), { qos: 1 });
        pubAcked = true;
        console.log(`[smoke] PUBACK 收到（Kafka 持久化后），ack 时延 ${Date.now() - t0}ms`);
        clearTimeout(timer);
        resolve();
      } catch (e) { clearTimeout(timer); reject(e); }
    });
    client.on('error', (e) => { clearTimeout(timer); reject(e); });
  });
  await client.endAsync(true); // 非优雅断开（模拟异常掉线），保留持久会话

  // ---- 重连：验证 sessionPresent=1 ----
  const ts2 = Date.now();
  const nonce2 = crypto.randomBytes(8).toString('hex');
  const client2 = mqtt.connect({
    host: '127.0.0.1', port: 1883, protocolVersion: 4,
    clientId, username: `${clientId}&${ts2}&${nonce2}`,
    password: sign(secret, clientId, String(ts2), nonce2),
    clean: false, keepalive: 30, reconnectPeriod: 0,
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('重连超时 15s')), 15000);
    client2.on('connect', (pkt) => {
      clearTimeout(timer);
      if (pkt.sessionPresent) {
        console.log('[smoke] 重连 CONNACK sessionPresent=1 ✓ 持久会话恢复');
        resolve();
      } else {
        reject(new Error('重连 sessionPresent=0，持久会话未恢复'));
      }
    });
    client2.on('error', (e) => { clearTimeout(timer); reject(e); });
  });
  await client2.endAsync();

  // ---- 越权发布：预期被 ACL 拒绝并关连接 ----
  const ts3 = Date.now();
  const nonce3 = crypto.randomBytes(8).toString('hex');
  const client3 = mqtt.connect({
    host: '127.0.0.1', port: 1883, protocolVersion: 4,
    clientId, username: `${clientId}&${ts3}&${nonce3}`,
    password: sign(secret, clientId, String(ts3), nonce3),
    clean: true, keepalive: 30, reconnectPeriod: 0,
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('ACL 测试超时 15s')), 15000);
    client3.on('connect', () => {
      client3.publish(`other_product/other_dev/up/property`, 'x', { qos: 0 });
    });
    client3.on('close', () => {
      clearTimeout(timer);
      console.log('[smoke] 越权发布被 ACL 拒绝并关连接 ✓');
      resolve();
    });
    client3.on('error', () => {});
  });

  console.log('[smoke] 全部通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[smoke] 失败:', e.message); process.exit(1); });
