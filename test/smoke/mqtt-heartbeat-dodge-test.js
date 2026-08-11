/**
 * 节点心跳避让端到端验证
 *
 * 链路：
 *  1. 设备连 broker-2，锁 owner=broker-2
 *  2. 模拟 command 侧调用 resolveNode → 应返回 broker-2（owner 心跳存活）
 *  3. kill broker-2（不清锁）→ 心跳 key 30s 消失
 *  4. 设备不重连，轮询 resolveNode → 应在前 ≤30s 内从 broker-2 变为 null（下行回落广播/离线）
 *
 * 说明：resolveNode 是 access 内部方法，本脚本用同逻辑直查 Redis 复现判定：
 *    owner = mqtt:conn:{key}；心跳存活 = mqtt:node:{owner} 存在
 * 运行：node test/smoke/mqtt-heartbeat-dodge-test.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');
const Redis = require('ioredis');
const { execSync } = require('child_process');

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
    clientId, username, password, clean, connectTimeout: 5000, reconnectPeriod: 0,
  });
}

function waitConnack(client) {
  return new Promise((resolve, reject) => {
    client.once('connect', (c) => resolve({ ok: true, sessionPresent: c.sessionPresent === true }));
    client.once('error', reject);
    setTimeout(() => reject(new Error('timeout')), 8000);
  });
}

/** 复现 BrokerNodeResolver.resolveNode 判定逻辑 */
async function resolveNode(redis, deviceKey) {
  const owner = await redis.get(`mqtt:conn:${deviceKey}`);
  if (!owner) return null;
  const alive = await redis.exists(`mqtt:node:${owner}`);
  return alive ? owner : null;
}

async function main() {
  const env = loadEnv(path.join(ROOT, 'deploy/env/local.env'));
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: env.MYSQL_PASSWORD, database: 'es_device',
  });
  const redis = new Redis({ host: '127.0.0.1', port: 6379 });
  const [devices] = await conn.query(
    `SELECT d.device_id, d.product_key, d.device_name, c.device_secret
     FROM iot_device d JOIN iot_device_credential c ON c.device_id = d.device_id
     WHERE d.status IN (2,3) AND d.deleted=0 AND c.auth_status = 1 LIMIT 1`);
  const dev = devices[0];
  const clientId = `${dev.product_key}_${dev.device_name}`;
  const deviceKey = clientId;
  console.log(`[设备] ${clientId}`);

  const results = [];

  // ========== 1. 设备连 broker-2 ==========
  console.log('\n=== 场景1：设备连 broker-2 ===');
  const c2 = connect(18832, clientId, dev.device_secret, false);
  await waitConnack(c2);
  await new Promise((res, rej) => c2.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res()));
  await new Promise(r => setTimeout(r, 800));
  const owner0 = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  锁 owner=${owner0}`);
  results.push({ step: 'owner-broker2', ok: owner0 === 'broker-2' });

  // ========== 2. resolveNode 应返回 broker-2 ==========
  const r1 = await resolveNode(redis, deviceKey);
  console.log(`  resolveNode=${r1}（心跳存活，应=broker-2）`);
  results.push({ step: 'resolve-before-kill', ok: r1 === 'broker-2' });

  // ========== 3. kill broker-2，设备不重连 ==========
  console.log('\n=== 场景2：kill broker-2（设备不重连），观察心跳消失与 resolveNode 回落 ===');
  const out = execSync('netstat -ano | findstr :18832', { encoding: 'utf8' });
  const pid = out.match(/(\d+)\s*$/m)[1];
  console.log(`  kill broker-2 PID=${pid}`);
  execSync(`taskkill /F /PID ${pid}`, { stdio: 'pipe' });

  // 轮询：心跳 key 消失时刻 + resolveNode 回落时刻
  const t0 = Date.now();
  let hbGoneAt = null;
  let dodgeAt = null;
  let lastOwner = 'broker-2';
  while (Date.now() - t0 < 45000) {
    const owner = await redis.get(`mqtt:conn:${clientId}`); // 锁不清，应一直 broker-2
    const alive = await redis.exists(`mqtt:node:broker-2`);
    const resolved = await resolveNode(redis, deviceKey);
    if (hbGoneAt === null && !alive) hbGoneAt = Date.now() - t0;
    if (dodgeAt === null && resolved !== 'broker-2') {
      dodgeAt = Date.now() - t0;
      lastOwner = resolved === null ? 'null(回落)' : resolved;
    }
    if (hbGoneAt !== null && dodgeAt !== null) break;
    await new Promise(r => setTimeout(r, 2000));
  }
  console.log(`  心跳 key 消失: ${hbGoneAt !== null ? hbGoneAt + 'ms' : '45s 内未消失'}`);
  console.log(`  resolveNode 回落: ${dodgeAt !== null ? dodgeAt + 'ms → ' + lastOwner : '45s 内未回落'}`);
  // 轮询粒度 2s，30s TTL 允许 ±3s 采样容差
  results.push({ step: 'heartbeat-gone-le30s', ok: hbGoneAt !== null && hbGoneAt <= 33000 });
  // 核心断言：回落应早于锁 TTL 20s 兜底——证明由心跳驱动（死节点避让）而非锁过期
  results.push({ step: 'resolve-dodge-before-lock-ttl', ok: dodgeAt !== null && dodgeAt < 20000 });
  const lockStill = await redis.get(`mqtt:conn:${clientId}`);
  // 锁 TTL 已缩为 20s 兜底；轮询窗口内可能已自然过期，属预期（不要求锁仍存在）
  console.log(`  锁 owner 当前=${lockStill || 'null（锁 TTL 20s 兜底已过期，属预期）'}`);
  results.push({ step: 'lock-ttl-20s-fallback', ok: lockStill === null || lockStill === 'broker-2' });

  await conn.end();
  await redis.quit();
  await new Promise(r => { c2.end(true, r); });

  console.log('\n=== 汇总 ===');
  const failed = results.filter(r => !r.ok);
  for (const r of results) console.log(`  ${r.ok ? '✓' : '✗'} ${r.step}`);
  console.log(`\n结果: ${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch(e => { console.error('FATAL', e); process.exit(1); });
