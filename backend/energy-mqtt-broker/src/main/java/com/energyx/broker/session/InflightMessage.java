package com.energyx.broker.session;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Outbound in-flight 消息（QoS1/2 待确认状态机）。
 *
 * <p>状态流转：
 * <pre>
 * QoS1: PUBLISH ──收到 PUBACK──▶ 移除
 * QoS2: PUBLISH(AWAITING_PUBREC) ──收到 PUBREC──▶ 发 PUBREL(AWAITING_PUBCOMP) ──收到 PUBCOMP──▶ 移除
 * </pre>
 * 持久会话会把 in-flight 集合同步到 Redis（mqtt:inflight:{deviceKey}），
 * 断线重连后按状态续传（AWAITING_PUBREC 重发 PUBLISH dup；AWAITING_PUBCOMP 重发 PUBREL）。</p>
 */
@Data
@AllArgsConstructor
public class InflightMessage {

    /** MQTT packetId（1~65535） */
    private int packetId;

    private String topic;

    private byte[] payload;

    /** 实际投递 QoS（取 min(publish.qos, 订阅授予 qos)） */
    private int qos;

    private boolean retain;

    /** 状态：0=AWAITING_PUBACK, 1=AWAITING_PUBREC, 2=AWAITING_PUBCOMP */
    private int state;

    private long enqueuedAtNanos;

    public static final int STATE_AWAITING_PUBACK = 0;
    public static final int STATE_AWAITING_PUBREC = 1;
    public static final int STATE_AWAITING_PUBCOMP = 2;
}
