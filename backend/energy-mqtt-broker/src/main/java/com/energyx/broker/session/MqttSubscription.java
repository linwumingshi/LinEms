package com.energyx.broker.session;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条订阅绑定（不可变值对象）：topicFilter + 授予的 QoS。
 *
 * <p>
 * 作为 Redis mqtt:subs:{deviceKey} SET 的成员存储（序列化见 {@link #encode()}）， 进程内同样以该形态在
 * Session.subscriptions 中做索引。equals 由 {@code @Data} 按字段生成，保证同 topicFilter+qos 去重。
 * </p>
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
