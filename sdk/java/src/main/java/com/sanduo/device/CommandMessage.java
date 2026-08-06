package com.sanduo.device;

import java.util.Map;

/**
 * 平台下发的指令消息（对端 payload 来自 energy-common CommandDownMessage 序列化）。
 *
 * <p>字段对应：{@code {commandId, deviceId, tenantId, productKey, deviceName,
 * command, params, qos, ts}}。设备收到后通过 {@link MqttDevice#ackCommand} 回 ack。</p>
 */
public final class CommandMessage {

    private String commandId;
    private String deviceId;
    private String tenantId;
    private String productKey;
    private String deviceName;
    private String command;
    private Map<String, Object> params;
    private long ts;
    /** 本地接收时间戳（墙钟），用于控制链路耗时统计。 */
    private long receivedAt;

    public String commandId() {
        return commandId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String productKey() {
        return productKey;
    }

    public String deviceName() {
        return deviceName;
    }

    public String command() {
        return command;
    }

    public Map<String, Object> params() {
        return params;
    }

    public long ts() {
        return ts;
    }

    public long receivedAt() {
        return receivedAt;
    }

    // ---- 便捷访问 ----

    public boolean hasId() {
        return commandId != null && !commandId.isBlank();
    }

    public Object param(String key) {
        if (params == null) {
            return null;
        }
        return params.get(key);
    }

    @Override
    public String toString() {
        return "CommandMessage{commandId='" + commandId + "', command='" + command + "', params=" + params + "}";
    }

    public static CommandMessage fromMap(Map<String, Object> raw) {
        CommandMessage m = new CommandMessage();
        m.commandId = str(raw.get("commandId"));
        m.deviceId = str(raw.get("deviceId"));
        m.tenantId = str(raw.get("tenantId"));
        m.productKey = str(raw.get("productKey"));
        m.deviceName = str(raw.get("deviceName"));
        m.command = str(raw.get("command"));
        Object p = raw.get("params");
        m.params = p instanceof Map ? castMap(p) : Map.of();
        m.ts = raw.get("ts") instanceof Number n ? n.longValue() : 0L;
        m.receivedAt = System.currentTimeMillis();
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ---- 链式 setter（供 JSON 反序列化） ----

    public CommandMessage setCommandId(String v) {
        this.commandId = v;
        return this;
    }

    public CommandMessage setCommand(String v) {
        this.command = v;
        return this;
    }

    public CommandMessage setParams(Map<String, Object> v) {
        this.params = v == null ? Map.of() : v;
        return this;
    }
}
