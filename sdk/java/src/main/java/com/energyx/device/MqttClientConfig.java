package com.energyx.device;

/**
 * MQTT 客户端连接配置。全部字段带安全默认值，可用 setter 覆盖。
 */
public final class MqttClientConfig {

    private String host = "127.0.0.1";
    private int port = 1883;
    /** CONNECT→CONNACK 等待上限（毫秒）。 */
    private int connectTimeoutMs = 10_000;
    /** keepalive 秒数（Broker 侧默认 60s 判定离线；模拟设备通常 30~60s）。 */
    private int keepAliveSeconds = 60;
    /** 是否订阅 down/command（压测只上报场景可关掉减少订阅开销）。 */
    private boolean subscribeCommand = true;
    /** 收到指令后是否自动回 ack（SUCCESS）。 */
    private boolean autoAck = true;
    /** 自动 ACK 的固定错误码（null 表示成功）。 */
    private String ackErrorCode = null;
    /** 连接建立后意外断开是否自动重连（指数退避）。 */
    private boolean autoReconnect = true;
    /** 重连退避基数（毫秒）。 */
    private int reconnectBackoffMs = 1000;
    /** 重连退避上限（毫秒）。 */
    private int reconnectMaxBackoffMs = 30_000;
    /** 是否启用 TLS（mqtts over 8883）。默认关闭。 */
    private boolean useTls = false;
    /** 信任的服务端证书 PEM 路径（自签名信任锚）。null 且 skipVerify=false 时走 JDK 默认信任库。 */
    private String tlsTrustCertFile = null;
    /** 跳过证书链与主机名校验（仅演示/自签名；生产必须 false）。 */
    private boolean tlsSkipVerify = false;

    public static MqttClientConfig defaults() {
        return new MqttClientConfig();
    }

    public String host() {
        return host;
    }

    public MqttClientConfig host(String host) {
        this.host = host;
        return this;
    }

    public int port() {
        return port;
    }

    public MqttClientConfig port(int port) {
        this.port = port;
        return this;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public MqttClientConfig connectTimeoutMs(int ms) {
        this.connectTimeoutMs = ms;
        return this;
    }

    public int keepAliveSeconds() {
        return keepAliveSeconds;
    }

    public MqttClientConfig keepAliveSeconds(int seconds) {
        this.keepAliveSeconds = seconds;
        return this;
    }

    public boolean subscribeCommand() {
        return subscribeCommand;
    }

    public MqttClientConfig subscribeCommand(boolean v) {
        this.subscribeCommand = v;
        return this;
    }

    public boolean autoAck() {
        return autoAck;
    }

    public MqttClientConfig autoAck(boolean v) {
        this.autoAck = v;
        return this;
    }

    public String ackErrorCode() {
        return ackErrorCode;
    }

    public MqttClientConfig ackErrorCode(String v) {
        this.ackErrorCode = v;
        return this;
    }

    public boolean autoReconnect() {
        return autoReconnect;
    }

    public MqttClientConfig autoReconnect(boolean v) {
        this.autoReconnect = v;
        return this;
    }

    public int reconnectBackoffMs() {
        return reconnectBackoffMs;
    }

    public MqttClientConfig reconnectBackoffMs(int ms) {
        this.reconnectBackoffMs = ms;
        return this;
    }

    public int reconnectMaxBackoffMs() {
        return reconnectMaxBackoffMs;
    }

    public MqttClientConfig reconnectMaxBackoffMs(int ms) {
        this.reconnectMaxBackoffMs = ms;
        return this;
    }

    public boolean useTls() {
        return useTls;
    }

    public MqttClientConfig useTls(boolean v) {
        this.useTls = v;
        return this;
    }

    public String tlsTrustCertFile() {
        return tlsTrustCertFile;
    }

    public MqttClientConfig tlsTrustCertFile(String v) {
        this.tlsTrustCertFile = v;
        return this;
    }

    public boolean tlsSkipVerify() {
        return tlsSkipVerify;
    }

    public MqttClientConfig tlsSkipVerify(boolean v) {
        this.tlsSkipVerify = v;
        return this;
    }
}
