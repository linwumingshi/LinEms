package com.energyx.simdevice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sim-device 命令行参数解析（纯函数，可单测）。
 *
 * <pre>
 * sim-device [--product snd_ess_pcs] [--device sim-dev-000001]
 *            [--secret-base sanduo-stress | --secret &lt;hex&gt;]
 *            [--broker 127.0.0.1:1883] [--autoack]
 * </pre>
 *
 * 密钥优先级：--secret 显式值 &gt; --secret-base 派生（默认 sanduo-stress）；
 * 派生公式与 test/stress Secrets.deriveSecret 一致（{@link DeviceSecret}）。
 */
public final class CliArgs {

    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)$");

    private final String product;
    private final String deviceName;
    private final String deviceSecret;
    private final String host;
    private final int port;
    private final boolean autoAck;

    private CliArgs(String product, String deviceName, String deviceSecret,
                    String host, int port, boolean autoAck) {
        this.product = product;
        this.deviceName = deviceName;
        this.deviceSecret = deviceSecret;
        this.host = host;
        this.port = port;
        this.autoAck = autoAck;
    }

    public String product() {
        return product;
    }

    public String deviceName() {
        return deviceName;
    }

    public String deviceSecret() {
        return deviceSecret;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean autoAck() {
        return autoAck;
    }

    /** 解析命令行参数；非法输入抛 {@link IllegalArgumentException}。 */
    public static CliArgs parse(String[] args) {
        String product = "snd_ess_pcs";
        String deviceName = "sim-dev-000001";
        String secretBase = "sanduo-stress";
        String explicitSecret = null;
        String host = "127.0.0.1";
        int port = 1883;
        boolean autoAck = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--product" -> product = value(args, ++i, "--product");
                case "--device" -> deviceName = value(args, ++i, "--device");
                case "--secret-base" -> secretBase = value(args, ++i, "--secret-base");
                case "--secret" -> explicitSecret = value(args, ++i, "--secret");
                case "--broker" -> {
                    String[] hp = parseBroker(value(args, ++i, "--broker"));
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                }
                case "--autoack" -> autoAck = true;
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        validateDeviceName(deviceName);
        String secret = explicitSecret != null
                ? explicitSecret
                : DeviceSecret.derive(secretBase, indexFromDeviceName(deviceName));
        return new CliArgs(product, deviceName, secret, host, port, autoAck);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("缺少 " + flag + " 的参数值");
        }
        return args[i];
    }

    /** host:port 拆分；端口必须为 1-65535。 */
    static String[] parseBroker(String broker) {
        String[] hp = broker.split(":", 2);
        if (hp.length != 2 || hp[0].isBlank()) {
            throw new IllegalArgumentException("broker 格式应为 host:port（当前: " + broker + "）");
        }
        int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("broker 端口非法: " + hp[1]);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("broker 端口越界: " + port);
        }
        return hp;
    }

    /** deviceName 禁 '_'/'&'（与 Broker 按最后一个 '_' 拆 clientId、username 分隔符冲突）。 */
    static void validateDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("--device 不能为空");
        }
        if (deviceName.contains("_") || deviceName.contains("&")) {
            throw new IllegalArgumentException(
                    "deviceName 不允许包含 '_' 或 '&'（当前: " + deviceName + "）");
        }
    }

    /** 从 deviceName 数字后缀解析 index（sim-dev-000001 → 1）。 */
    static int indexFromDeviceName(String deviceName) {
        Matcher m = TRAILING_DIGITS.matcher(deviceName);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "使用 --secret-base 派生密钥需要 --device 以数字结尾（当前: " + deviceName + "）");
        }
        return Integer.parseInt(m.group(1));
    }

    public static String usage() {
        return """
                用法: sim-device [选项]
                  --product <pk>       产品标识（默认 snd_ess_pcs）
                  --device <dn>        设备名（默认 sim-dev-000001，须已注册）
                  --secret-base <s>    密钥派生基串（默认 sanduo-stress）；
                                       密钥 = hex(SHA-256(<s>:<index>))，index 取 --device 数字后缀
                  --secret <hex>       显式设备密钥（优先于 --secret-base 派生）
                  --broker <host:port> Broker 地址（默认 127.0.0.1:1883）
                  --autoack            启动即自动回 ACK
                  --help               显示本帮助""";
    }
}
