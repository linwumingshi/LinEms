/**
 * 多节点故障演练：节点宕机 → 设备重连其他节点 → 会话恢复 + 下行可达
 *
 * 流程：
 *  1. 设备连 broker-2，订阅下行，建立持久会话（cleanSession=false）
 *  2. 模拟 broker-2 宕机（kill 进程）——由外部传入或本脚本直接 Stop-Process
 *  3. 设备重连 broker-3（模拟 LB 调度到其他节点）→ 应 sessionPresent=1
 *  4. 通过 Kafka 发下行到设备（应走 mqtt.down.broker-3 或按锁 owner 定向）→ 设备应收到
 *
 * 运行：node test/smoke/mqtt-failover-test.js [killBroker2=true|false] [waitTtl=true|false]
 *   第二参：true=kill broker-2（默认）；false=不 kill 只验证跨节点会话跟随
 *   第三参：true=等锁 TTL 自然过期接管（默认）；false=手动清锁快速验证
 * 依赖：mysql2、mqtt、kafkajs、ioredis
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mysql = require('mysql2/promise');
const mqtt = require('mqtt');
const { Kafka } = require('kafkajs');
const Redis = require('ioredis');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '../..');
const KILL_BROKER2 = process.argv[2] !== 'false';

const BROKER2_MGMT_PID = null; // 运行时查端口获取

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
    clientId, username, password, clean,
    connectTimeout: 5000, reconnectPeriod: 0,
  });
}

function waitConnack(client) {
  return new Promise((resolve, reject) => {
    client.once('connect', (connack) => resolve({ ok: true, sessionPresent: connack.sessionPresent === true }));
    client.once('error', reject);
    setTimeout(() => reject(new Error('CONNECT 超时')), 8000);
  });
}

async function findBroker2Pid() {
  // 查 18832 端口占用进程（Windows netstat）
  const out = execSync('netstat -ano | findstr :18832', { encoding: 'utf8' });
  const m = out.match(/(\d+)\s*$/m);
  if (!m) throw new Error('未找到 broker-2 进程（18832 端口）');
  return m[1];
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
  console.log(`[设备] ${clientId}`);

  const results = [];

  // ========== 1. 连 broker-2 建持久会话 + 订阅 ==========
  console.log('\n=== 场景1：设备连 broker-2 建立持久会话 ===');
  const c2 = connect(18832, clientId, dev.device_secret, false);
  const r1 = await waitConnack(c2);
  await new Promise((res, rej) => {
    c2.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
  });
  await new Promise((r) => setTimeout(r, 800));
  const owner1 = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  连接 broker-2 → sessionPresent=${r1.sessionPresent} 锁owner=${owner1}`);
  results.push({ step: 'initial-connect-broker2', ok: owner1 === 'broker-2' });

  if (KILL_BROKER2) {
    // ========== 2. 杀 broker-2 ==========
    console.log('\n=== 场景2：kill broker-2（模拟节点宕机） ===');
    const pid = await findBroker2Pid();
    console.log(`  broker-2 PID=${pid}，执行 kill...`);
    try {
      execSync(`taskkill /F /PID ${pid}`, { stdio: 'pipe' });
      console.log('  broker-2 已 kill');
    } catch (e) {
      console.log(`  kill 失败（可能需管理员）: ${e.stderr?.toString().trim() || e.message}`);
    }
    await new Promise((r) => setTimeout(r, 2000));
    const lockOwner = await redis.get(`mqtt:conn:${clientId}`);
    console.log(`  kill 后连接锁 owner=${lockOwner || 'null'}`);
    const waitTtl = process.argv[3] !== 'false'; // 第三参 false = 立即接管（清锁）；默认等锁 TTL 自然过期
    if (waitTtl) {
      // 连接锁 TTL 配置 conn-lock-ttl-seconds=60；锁续期随节点死亡停止，等它自然过期
      console.log('  等待连接锁 TTL 自然过期（60s）...');
      let waited = 0;
      while (waited < 75) {
        const o = await redis.get(`mqtt:conn:${clientId}`);
        if (!o) { console.log(`  锁已过期释放（等待 ${waited}s）`); break; }
        await new Promise((r) => setTimeout(r, 5000));
        waited += 5;
      }
      const after = await redis.get(`mqtt:conn:${clientId}`);
      console.log(`  等待后锁 owner=${after || 'null（已过期）'}`);
      if (after) { console.log('  WARN: 60s 后锁仍未过期，继续尝试接管'); }
    }
    else {
      // 兼容旧行为：主动清锁模拟锁过期后的接管
      try {
        await redis.del(`mqtt:conn:${clientId}`);
        console.log('  手动清理连接锁（模拟锁过期，快速验证接管路径）');
      } catch (e) {
        console.log(`  清锁失败: ${e.message}`);
      }
    }
  }
  else {
    // 非杀节点模式：先主动断开 broker-2 连接，再重连 broker-3 验证会话跟随
    console.log('\n=== 场景2（基线）：主动断开 broker-2，验证会话跟随 ===');
    await new Promise((r) => { c2.end(true, r); });
    await new Promise((r) => setTimeout(r, 800));
    console.log('  broker-2 连接已断开（持久会话应保留在 Redis）');
  }

  // ========== 3. 设备重连 broker-3，验证会话恢复 ==========
  console.log('\n=== 场景3：设备重连 broker-3，会话恢复 ===');
  const c3 = connect(18833, clientId, dev.device_secret, false);
  const r3 = await waitConnack(c3);
  await new Promise((res, rej) => {
    c3.subscribe(`${dev.product_key}/${dev.device_name}/down/#`, { qos: 1 }, (e) => e ? rej(e) : res());
  });
  await new Promise((r) => setTimeout(r, 800));
  const owner3 = await redis.get(`mqtt:conn:${clientId}`);
  console.log(`  重连 broker-3 → sessionPresent=${r3.sessionPresent} 锁owner=${owner3}`);
  results.push({ step: 'reconnect-broker3-session', ok: r3.sessionPresent === true && owner3 === 'broker-3' });

  // ========== 4. 下行定向到新节点 ==========
  console.log('\n=== 场景4：故障后下行定向（应走 mqtt.down.broker-3） ===');
  const kafka = new Kafka({ clientId: 'failover-test', brokers: ['127.0.0.1:9092'] });
  const producer = kafka.producer();
  await producer.connect();
  const envelope = Buffer.from(JSON.stringify({
    type: 'DOWNLINK',
    topic: `${dev.product_key}/${dev.device_name}/down/command`,
    payload: Buffer.from(JSON.stringify({ cmd: 'discharge', power: 60 })).toString('base64'),
    sourceNode: 'broker-1',
    ts: Date.now(),
  }));
  await producer.send({
    topic: `mqtt.down.broker-3`,
    messages: [{ key: clientId, value: envelope }],
  });
  const got = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), 8000);
    c3.on('message', (t, p) => { clearTimeout(timer); resolve({ topic: t, payload: p.toString() }); });
  });
  console.log(`  设备收到: ${got ? JSON.stringify(got) : '无（超时）'} `);
  results.push({ step: 'downlink-after-failover', ok: got !== null });
  await producer.disconnect();

  await conn.end();
  await redis.quit();

  console.log('\n=== 汇总 ===');
  const failed = results.filter((r) => !r.ok);
  for (const r of results) console.log(`  ${r.ok ? '✓' : '✗'} ${r.step}`);
  console.log(`\n结果: ${results.length - failed.length}/${results.length} 通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
