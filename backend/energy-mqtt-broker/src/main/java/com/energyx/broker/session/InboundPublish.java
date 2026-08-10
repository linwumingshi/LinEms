package com.energyx.broker.session;

/**
 * Inbound QoS2 待 PUBREL 确认的发布（进 Session.inboundQos2，packetId → 报文）。 设备上行以 QoS1 为主（Phase 1
 * §6.1），QoS2 入站按「收到 PUBREL 才路由」实现，内存态不持久化。
 */
public record InboundPublish(String topic, byte[] payload, int qos) {
}
