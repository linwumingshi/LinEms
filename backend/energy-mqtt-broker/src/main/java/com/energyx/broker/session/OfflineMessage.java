package com.energyx.broker.session;

import lombok.Data;

import java.util.Base64;

/**
 * 离线消息（持久会话离线期间积压，Redis mqtt:offline:{deviceKey} List 成员，JSON 序列化）。
 */
@Data
public class OfflineMessage {

    private String topic;
    private String payloadBase64;
    private int qos;

    public OfflineMessage() {
    }

    public OfflineMessage(String topic, byte[] payload, int qos) {
        this.topic = topic;
        this.payloadBase64 = Base64.getEncoder().encodeToString(payload);
        this.qos = qos;
    }

    public byte[] payload() {
        return Base64.getDecoder().decode(payloadBase64);
    }
}
