package com.sanduo.energy.common.mqtt;

/**
 * 设备上行报文类型（Phase 1 §6.1）：`{productKey}/{deviceName}/up/{type}`。
 *
 * <ul>
 *   <li>property —— 属性上报（周期/变化即报）；</li>
 *   <li>event    —— 设备事件（告警/故障/状态迁移）；</li>
 *   <li>lifecycle —— 设备自报上下线（重启/断网自报）；</li>
 *   <li>ack      —— 对下行指令的应答。</li>
 * </ul>
 */
public enum MqttUpType {

    PROPERTY("property"),
    EVENT("event"),
    LIFECYCLE("lifecycle"),
    ACK("ack");

    private final String segment;

    MqttUpType(String segment) {
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }

    /** 按 Topic 段反查类型；不识别返回 null（调用方按非法上行处理）。 */
    public static MqttUpType from(String segment) {
        if (segment == null || segment.isEmpty()) {
            return null;
        }
        for (MqttUpType t : values()) {
            if (t.segment.equals(segment)) {
                return t;
            }
        }
        return null;
    }
}
