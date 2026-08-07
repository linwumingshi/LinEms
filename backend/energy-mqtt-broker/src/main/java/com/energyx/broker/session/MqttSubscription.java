package com.energyx.broker.session;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条订阅绑定：topicFilter + 授予的 QoS。
 */
@Data
@AllArgsConstructor
public class MqttSubscription {

    private String topicFilter;

    private int qos;

    /** 序列化契约：topicFilter@qos，用于 Redis mqtt:subs SET 成员 */
    public String encode() {
        return topicFilter + "@" + qos;
    }

    public static MqttSubscription decode(String member) {
        int idx = member.lastIndexOf('@');
        if (idx < 0) {
            throw new IllegalArgumentException("非法订阅序列化格式: " + member);
        }
        return new MqttSubscription(member.substring(0, idx), Integer.parseInt(member.substring(idx + 1)));
    }
}
