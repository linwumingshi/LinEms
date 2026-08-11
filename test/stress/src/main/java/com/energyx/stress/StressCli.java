package com.energyx.stress;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压测工具入口：子命令分发。
 *
 * <pre>
 * java -jar stress.jar seed       [--count 10000 --product snd_ess_pcs ...]  造数注册设备
 * java -jar stress.jar connect    [--count 100000 --concurrency 500 ...]     连接压测
 * java -jar stress.jar throughput [--count 10000 --rate 20 --duration 60]    吞吐压测
 * java -jar stress.jar control    [--count 200 --concurrency 50 ...]         控制链路 P99
 * </pre>
 *
 * <p>全部参数均有安全默认值；MySQL 密码经环境变量 MYSQL_PASSWORD 注入（P0-4 密钥外置，仓库零明文）。</p>
 */
public final class StressCli {

    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/es_device"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";
    private static final String MYSQL_USER = "root";
    /** 本地开发 MySQL 密码：经环境变量 MYSQL_PASSWORD 注入（P0-4 密钥外置），仓库内无明文默认值 */
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "");

    private StressCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(2);
        }
        String cmd = args[0];
        Map<String, String> opts = parseArgs(Arrays.copyOfRange(args, 1, args.length));
        int code;
        try {
            code = switch (cmd) {
                case "seed" -> runSeed(opts);
                case "connect" -> runConnect(opts);
                case "throughput" -> runThroughput(opts);
                case "control" -> runControl(opts);
                case "help", "-h", "--help" -> {
                    printUsage();
                    yield 0;
                }
                default -> {
                    System.err.println("未知子命令: " + cmd);
                    printUsage();
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("[Stress] 执行失败: " + e.getMessage());
            e.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    // ------------------------------------------------------------------
    // 子命令
    // ------------------------------------------------------------------

    private static int runSeed(Map<String, String> o) throws Exception {
        SeedDevices.Args a = new SeedDevices.Args(str(o, "jdbc-url", MYSQL_URL),
                str(o, "user", MYSQL_USER), str(o, "password", MYSQL_PASSWORD));
        a.tenantId = lng(o, "tenant", 1L);
        a.enterpriseId = o.containsKey("enterprise") ? lng(o, "enterprise", 1L) : null;
        a.stationId = o.containsKey("station") ? lng(o, "station", 1L) : null;
        a.productKey = str(o, "product", "snd_ess_pcs");
        a.deviceType = str(o, "device-type", "PCS");
        a.count = num(o, "count", 1000);
        a.startIndex = num(o, "start-index", 1);
        a.secretBase = str(o, "secret-base", "sanduo-stress");
        a.deviceIdBase = o.containsKey("device-id-base")
                ? lng(o, "device-id-base", SeedDevices.DEVICE_ID_BASE) : SeedDevices.DEVICE_ID_BASE;
        return SeedDevices.run(a);
    }

    private static int runConnect(Map<String, String> o) throws Exception {
        ConnectLoad.Args a = new ConnectLoad.Args();
        a.host = str(o, "host", "127.0.0.1");
        a.port = num(o, "port", 1883);
        a.count = num(o, "count", 1000);
        a.concurrency = num(o, "concurrency", 200);
        a.connectTimeoutMs = num(o, "connect-timeout", 10_000);
        a.keepAliveSeconds = num(o, "keepalive", 120);
        a.subscribe = bool(o, "subscribe", false);
        a.holdSeconds = num(o, "hold-seconds", 0);
        a.ioThreads = num(o, "io-threads", ConnectUtil.ioThreads());
        a.productKey = str(o, "product", "snd_ess_pcs");
        a.secretBase = str(o, "secret-base", "sanduo-stress");
        a.tls = bool(o, "tls", false);
        a.tlsSkipVerify = bool(o, "tls-skip-verify", false);
        a.tlsTrustCertFile = o.get("tls-cert"); // 无值即 null → 不固定信任
        return ConnectLoad.run(a);
    }

    private static int runThroughput(Map<String, String> o) throws Exception {
        ThroughputLoad.Args a = new ThroughputLoad.Args();
        a.host = str(o, "host", "127.0.0.1");
        a.port = num(o, "port", 1883);
        a.count = num(o, "count", 1000);
        a.ratePerDevice = num(o, "rate", 10);
        a.durationSec = num(o, "duration", 60);
        a.concurrency = num(o, "concurrency", 200);
        a.workerThreads = num(o, "workers", 8);
        a.ioThreads = num(o, "io-threads", ConnectUtil.ioThreads());
        a.connectTimeoutMs = num(o, "connect-timeout", 10_000);
        a.productKey = str(o, "product", "snd_ess_pcs");
        a.secretBase = str(o, "secret-base", "sanduo-stress");
        return ThroughputLoad.run(a);
    }

    private static int runControl(Map<String, String> o) throws Exception {
        ControlLatency.Args a = new ControlLatency.Args();
        a.gateway = str(o, "gateway", "http://127.0.0.1:8000");
        a.host = str(o, "host", "127.0.0.1");
        a.port = num(o, "port", 1883);
        a.count = num(o, "count", 100);
        a.concurrency = num(o, "concurrency", 20);
        a.timeoutMs = num(o, "timeout", 10_000);
        a.connectTimeoutMs = num(o, "connect-timeout", 10_000);
        a.ioThreads = num(o, "io-threads", ConnectUtil.ioThreads());
        a.productKey = str(o, "product", "snd_ess_pcs");
        a.secretBase = str(o, "secret-base", "sanduo-stress");
        return ControlLatency.run(a);
    }

    // ------------------------------------------------------------------
    // 参数解析
    // ------------------------------------------------------------------

    /** --key value / --flag（无值视为 true）。 */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                continue;
            }
            String key = token.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                map.put(key, args[++i]);
            } else {
                map.put(key, "true");
            }
        }
        return map;
    }

    private static String str(Map<String, String> o, String key, String def) {
        return o.getOrDefault(key, def);
    }

    private static int num(Map<String, String> o, String key, int def) {
        String v = o.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 必须是整数: " + v);
        }
    }

    private static long lng(Map<String, String> o, String key, long def) {
        String v = o.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 必须是整数: " + v);
        }
    }

    private static boolean bool(Map<String, String> o, String key, boolean def) {
        String v = o.get(key);
        if (v == null) {
            return def;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static void printUsage() {
        System.out.println("""
                EnergyX 平台压测工具 (energyx-stress)
                用法: java -jar stress.jar <子命令> [--key value ...]

                  seed        设备造数注册（写入 es_device 库）
                      --count 10000 --product snd_ess_pcs --tenant 1
                      --station <stationId> --start-index <N> --secret-base sanduo-stress
                      --jdbc-url <url> --user root --password ${MYSQL_PASSWORD}
                      # --station 把设备挂到电站（EMS 需量/收益按站查询必需）；
                      # --start-index 从序号 N 造起（默认 1），多产品造数避开已占用设备名/号段
                  connect     连接压测（建连速率 / 延迟分位 / 保持连接）
                      --count 100000 --concurrency 500 --host 127.0.0.1 --port 1883
                      --subscribe false --hold-seconds 60 --io-threads 16
                      --tls --tls-skip-verify | --tls --tls-cert <server-cert.pem>
                  throughput  吞吐压测（目标 = count × rate msg/s）
                      --count 10000 --rate 20 --duration 60 --workers 8
                      --product snd_ess_pcs|snd_ess_meter   # METER 只报 importPower
                  control     控制链路 P99（命令下发→设备 ACK→SUCCESS，目标 P99 ≤ 500ms）
                      --count 200 --concurrency 50 --gateway http://127.0.0.1:8000 --timeout 10000

                全部子命令均需先 seed 注册对应设备（productKey/secretBase 一致）。
                默认 MySQL: 127.0.0.1:3306 root/${MYSQL_PASSWORD}; 默认 Broker: 127.0.0.1:1883。
                """);
    }
}
