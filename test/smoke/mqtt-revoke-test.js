/**
 * 凭据失效广播验证（P2-6）：设备在线时 Redis PUBLISH mqtt:cred:revoked → broker 应踢线。
 * 手写 RESP 协议发 PUBLISH（不依赖 redis 客户端库）。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const net = require('net');
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
function redisPublish(host, port, channel, message) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, host);
    const build = (...args) => {
      let out = `*${args.length}\r\n`;
      for (const a of args) {
        const b = Buffer.from(String(a));
        out += `$${b.length}\r\n${a}\r\n`;
      }
      return out;
    };
    sock.on('connect', () => {
      sock.write(build('PUBLISH', channel, message));
    });
    let buf = '';
    sock.on('data', (d) => {
      buf += d.toString();
      if (buf.includes('\r\n')) {
        sock.end();
        resolve(buf.trim());
      }
    });
    sock.on('error', reject);
    setTimeout(() => reject(new Error('redis publish 超时')), 5000).unref();
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
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const ts = Date.now();
  const nonce = crypto.randomBytes(8).toString('hex');
  const a = { username: `${clientId}&${ts}&${nonce}`, password: sign(dev.device_secret, clientId, String(ts), nonce) };
  console.log(`[revoke] 设备 clientId=${clientId}`);

  // 1. 设备连接在线
  const client = mqtt.connect({ host: '127.0.0.1', port: 18831, protocolVersion: 4,
    clientId, username: a.username, password: a.password, clean: true, keepalive: 30, reconnectPeriod: 0 });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('连接超时')), 15000);
    client.on('connect', () => { clearTimeout(t); console.log('[revoke] 设备已连接'); resolve(); });
    client.on('error', (e) => { clearTimeout(t); reject(e); });
  });

  // 2. Redis PUBLISH 凭据失效
  const closed = new Promise((resolve) => {
    client.on('close', () => { console.log('[revoke] 设备连接被 broker 关闭（踢线成功）✓'); resolve(); });
  });
  const reply = await redisPublish('127.0.0.1', 6379, 'mqtt:cred:revoked', clientId);
  console.log(`[revoke] redis PUBLISH reply=${reply}`);

  // 3. 等待被踢
  await Promise.race([closed, new Promise((_, rej) => setTimeout(() => rej(new Error('设备未被踢线')), 10000))]);
  console.log('[revoke] 凭据失效广播验证通过 ✓');
  process.exit(0);
}

main().catch((e) => { console.error('[revoke] 失败:', e.message); process.exit(1); });
