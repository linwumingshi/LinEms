const mqtt = require('mqtt');

const url = 'tcp://127.0.0.1:18831';
// 测试用的非法凭据：健康 broker 应快速返回 CONNACK(refused)，卡死则超时无响应
const client = mqtt.connect(url, {
  clientId: 'probe_dummy_' + Date.now(),
  username: 'dummy&1&nonce',
  password: 'wrong',
  connectTimeout: 12000,
  reconnectPeriod: 0,
  clean: true,
});

const t0 = Date.now();
let done = false;
function finish(msg) {
  if (done) return;
  done = true;
  console.log('[' + (Date.now() - t0) + 'ms] ' + msg);
  client.end(true);
  process.exit(0);
}
client.on('connect', () => finish('CONNECTED (unexpected for bad creds)'));
client.on('error', (e) => finish('ERROR: ' + e.message));
client.on('close', () => { if (!done) finish('CLOSED (no CONNACK, broker dropped)'); });
setTimeout(() => finish('TIMEOUT: broker did not respond to CONNECT within 12s'), 13000);
