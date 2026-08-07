package com.sanduo.simdevice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sanduo.device.CommandMessage;
import com.sanduo.device.DeviceIdentity;
import com.sanduo.device.DeviceListener;
import com.sanduo.device.MqttClientConfig;
import com.sanduo.device.MqttDevice;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 封装 MqttDevice 生命周期与薄封装：connect/disconnect/reconnect、上报、
 * 手动/自动 ACK。仅依赖 SDK，不涉及 REPL 的 IO。
 *
 * <p>自动回 ACK 由本类自行实现（不依赖 config.autoAck），以便运行时切换：
 * SDK 收到指令后回调 listener，若 {@code autoAck} 为真则立即回 SUCCESS。</p>
 */
public final class Connector {

    private final DeviceIdentity identity;
    private final String host;
    private final int port;
    private final PendingCommands pending;
    private final MqttClientConfig config;
    private final AtomicBoolean autoAck = new AtomicBoolean(false);
    private volatile MqttDevice device;
    private volatile Consumer<CommandMessage> onCommandArrived;
    private volatile Consumer<Throwable> onError;

    public Connector(DeviceIdentity identity, String host, int port, PendingCommands pending) {
        this.identity = identity;
        this.host = host;
        this.port = port;
        this.pending = pending;
        this.config = MqttClientConfig.defaults()
                .host(host).port(port)
                .connectTimeoutMs(10_000)
                .keepAliveSeconds(60)
                .subscribeCommand(true)
                .autoAck(false)        // 手动 ACK 由本类 listener 控制（支持运行时切换）
                .autoReconnect(false); // 交互式工具：断开不静默重连，由用户显式 reconnect
    }

    /** REPL 注册：收到下行命令时回调（用于中断打印）。 */
    public void setOnCommandArrived(Consumer<CommandMessage> callback) {
        this.onCommandArrived = callback;
    }

    /** REPL 注册：SDK 异常（下行解析失败/通道异常）回调。 */
    public void setOnError(Consumer<Throwable> callback) {
        this.onError = callback;
    }

    /** 建立（或重建）连接。返回展示用结果行；失败返回含原因/建议的提示行。 */
    public String connect() {
        if (isConnected()) {
            return "已连接 " + identity.clientId() + " @ " + host + ":" + port;
        }
        MqttDevice d = new MqttDevice(identity, config, listener);
        device = d;
        try {
            d.connect();
            return "已连接 " + identity.clientId() + " @ " + host + ":" + port;
        } catch (Exception e) {
            // 覆盖两类失败：CONNACK 拒绝/超时（IllegalStateException），以及
            // SDK syncUninterruptibly() 透传的原始连接异常（ConnectException 等）。
            // SDK 在该透传路径上不会调用 doClose，自建 EventLoopGroup 线程泄漏会导致
            // JVM 在退出时不结束，这里尽力关闭释放（与 disconnect() 相同的兜底）。
            try {
                d.close();
            } catch (Exception ignore) {
                // 尽力释放，失败不阻塞提示
            }
            device = null;
            return describeConnectError(e);
        }
    }

    public String disconnect() {
        MqttDevice d = device;
        if (d == null || !d.isConnected()) {
            device = null;
            return "未连接";
        }
        try {
            d.close();
        } catch (Exception ignore) {
            // 尽力优雅断开，失败不阻塞
        }
        device = null;
        return "已断开 " + identity.clientId();
    }

    public String reconnect() {
        disconnect();
        return connect();
    }

    public String status() {
        return "连接: " + (isConnected() ? "已连接" : "未连接") + "\n"
                + "clientId: " + identity.clientId() + "\n"
                + "broker: " + host + ":" + port + "\n"
                + "待处理命令: " + pending.pendingCount() + "\n"
                + "自动回 ACK: " + (autoAck.get() ? "on" : "off");
    }

    public boolean isConnected() {
        MqttDevice d = device;
        return d != null && d.isConnected();
    }

    public String clientId() {
        return identity.clientId();
    }

    public String broker() {
        return host + ":" + port;
    }

    public boolean autoAck() {
        return autoAck.get();
    }

    public void setAutoAck(boolean on) {
        autoAck.set(on);
    }

    public void publishProperty(Map<String, Object> props) {
        requireConnected();
        try {
            device.publishProperty(props);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("属性序列化失败", e);
        }
    }

    public void publishEvent(String name, int severity, String code, Map<String, Object> data) {
        requireConnected();
        try {
            device.publishEvent(name, severity, code, data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
    }

    public void publishLifecycle(String eventType, String ip) {
        requireConnected();
        try {
            device.publishLifecycle(eventType, ip);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("生命周期序列化失败", e);
        }
    }

    public void ackCommand(String commandId, String status) {
        requireConnected();
        device.ackCommand(commandId, status, null,
                status.equals("SUCCESS") ? Map.of("exec", "ok") : null);
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("MQTT 未连接，先 connect");
        }
    }

    // ------------------------------------------------------------------
    // 回调（Netty IO 线程触发，禁止阻塞）
    // ------------------------------------------------------------------

    private final DeviceListener listener = new DeviceListener() {
        @Override
        public void onConnected(DeviceIdentity identity) {
        }

        @Override
        public void onCommand(DeviceIdentity identity, CommandMessage command) {
            pending.add(command);
            if (autoAck.get()) {
                MqttDevice d = device;
                if (d != null) {
                    d.ackCommand(command);
                }
            }
            Consumer<CommandMessage> cb = onCommandArrived;
            if (cb != null) {
                cb.accept(command);
            }
        }

        @Override
        public void onDisconnected(DeviceIdentity identity, String reason) {
        }

        @Override
        public void onError(DeviceIdentity identity, Throwable cause) {
            Consumer<Throwable> cb = onError;
            if (cb != null) {
                cb.accept(cause);
            }
        }
    };

    // ------------------------------------------------------------------
    // 连接错误 → 中文提示
    // ------------------------------------------------------------------

    /** CONNACK 返回码 → 中文说明。 */
    static String connackHint(int code) {
        return switch (code) {
            case 1 -> "协议版本不支持（MQTT 3.1.1）";
            case 2 -> "clientId 非法";
            case 4 -> "密码错误或设备未注册/未激活（校验 --secret/--device 与 seed 一致）";
            case 5 -> "未授权";
            default -> "连接被拒绝 code=" + code;
        };
    }

    static String describeConnectError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("连接被拒绝 code=")) {
            // 真实 SDK 消息：code= 后可能是数字（老格式）或 CONNACK 枚举名
            // （netty MqttConnectReturnCode 不重写 toString，故按常量名打印）。
            int idx = msg.indexOf("code=") + "code=".length();
            int end = idx;
            while (end < msg.length() && !Character.isWhitespace(msg.charAt(end))) {
                end++;
            }
            String token = msg.substring(idx, end);
            if (token.isEmpty()) {
                return "连接被拒绝: " + msg;
            }
            boolean allDigits = true;
            for (int i = 0; i < token.length(); i++) {
                if (!Character.isDigit(token.charAt(i))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                try {
                    return "连接被拒绝: " + connackHint(Integer.parseInt(token));
                } catch (NumberFormatException nfe) {
                    return "连接被拒绝: " + msg;
                }
            }
            return connackNameHint(token);
        }
        if (msg.contains("CONNACK 超时") || msg.contains("TCP 连接失败")
                || e instanceof java.io.IOException) {
            // IOException 覆盖 SDK 透传的原始连接失败（ConnectException 等）
            return "连接失败: " + msg + "（检查 broker 是否在跑，或 --broker 地址）";
        }
        return "连接失败: " + msg;
    }

    /** CONNACK 枚举名 → 中文说明（与 connackHint(int) 的映射一致）。 */
    private static String connackNameHint(String name) {
        String hint = switch (name) {
            case "CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION" -> "协议版本不支持（MQTT 3.1.1）";
            case "CONNECTION_REFUSED_IDENTIFIER_REJECTED" -> "clientId 非法";
            case "CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD" -> "密码错误或设备未注册/未激活（校验 --secret/--device 与 seed 一致）";
            case "CONNECTION_REFUSED_NOT_AUTHORIZED" -> "未授权";
            default -> null;
        };
        if (hint == null) {
            return "连接被拒绝 " + name;
        }
        return "连接被拒绝: " + hint;
    }
}
