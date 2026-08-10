/**
 * 遗嘱 Redis 持久化验证（节点宕机不丢的核心逻辑）：
 *  CONNECT 带遗嘱 → session hash 应写入 will；非优雅断开 → 遗嘱投递 + will 删除；
 *  优雅断开 → will 删除（不补投）。补投路径（kill broker 场景）由代码逻辑保证。
 * 手写 RESP HGET 检查 Redis。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const net = require('net');
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
function connect(secret, clientId, will) {
  const a = auth(secret, clientId);
  const opts = { host: '127.0.0.1', port: MQTT_PORT, protocolVersion: 4,
    clientId, username: a.username, password: a.password, clean: false, keepalive: 30, reconnectPeriod: 0 };
  if (will) opts.will = will;
  return mqtt.connect(opts);
}
function redisCmd(host, port, args) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, host);
    const build = () => {
      let out = `*${args.length}\r\n`;
      for (const a of args) {
        const b = Buffer.from(String(a));
        out += `$${b.length}\r\n${a}\r\n`;
      }
      return out;
    };
    sock.on('connect', () => sock.write(build()));
    let buf = '';
    sock.on('data', (d) => { buf += d.toString(); if (buf.includes('\r\n')) { sock.end(); resolve(buf.trim()); } });
    sock.on('error', reject);
    setTimeout(() => reject(new Error('redis 超时')), 5000).unref();
  });
}
async function hgetWill(clientId) {
  const reply = await redisCmd('127.0.0.1', 6379, ['HGET', `mqtt:session:${clientId}`, 'will']);
  return reply === '$-1' ? null : reply;
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
  const willTopic = `${dev.product_key}/${dev.device_name}/up/event`;
  console.log(`[will] 设备 clientId=${clientId}`);

  // 1. 带遗嘱连接（持久会话）
  const c1 = connect(secret, clientId, { topic: willTopic, payload: '{"eventType":"OFFLINE"}', qos: 1, retain: false });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    c1.on('connect', () => { clearTimeout(t); resolve(); });
    c1.on('error', reject);
  });
  await new Promise((r) => setTimeout(r, 1500)); // 等恢复段写 will
  let will = await hgetWill(clientId);
  console.log(`[will] CONNECT 后 Redis will 字段: ${will ? '存在 ✓' : '缺失 ✗'}`);
  if (!will) throw new Error('遗嘱未持久化到 Redis');

  // 2. 非优雅断开 → 遗嘱投递 + will 删除
  await c1.endAsync(true);
  await new Promise((r) => setTimeout(r, 2500));
  will = await hgetWill(clientId);
  console.log(`[will] 非优雅断开后 will: ${will === null ? '已删除 ✓' : '仍存在 ✗'}`);
  if (will !== null) throw new Error('非优雅断开后遗嘱未删除');

  // 3. 再带遗嘱连接 → 优雅断开 → will 删除（不补投）
  const c2 = connect(secret, clientId, { topic: willTopic, payload: '{"eventType":"OFFLINE"}', qos: 1, retain: false });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    c2.on('connect', () => { clearTimeout(t); resolve(); });
    c2.on('error', reject);
  });
  await new Promise((r) => setTimeout(r, 1500));
  will = await hgetWill(clientId);
  console.log(`[will] 第二次 CONNECT 后 will: ${will ? '存在 ✓' : '缺失 ✗'}`);
  await c2.endAsync(); // 优雅（发送 DISCONNECT）
  await new Promise((r) => setTimeout(r, 2000));
  will = await hgetWill(clientId);
  console.log(`[will] 优雅断开后 will: ${will === null ? '已删除 ✓' : '仍存在 ✗'}`);
  if (will !== null) throw new Error('优雅断开后遗嘱未删除');

  console.log('[will] 遗嘱 Redis 持久化验证通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[will] 失败:', e.message); process.exit(1); });
