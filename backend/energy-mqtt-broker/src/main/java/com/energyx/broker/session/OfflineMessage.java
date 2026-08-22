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

	/** 由 topic/原始 payload/qos 构造，payload 立即 base64 编码落库 */
	public OfflineMessage(String topic, byte[] payload, int qos) {
		this.topic = topic;
		this.payloadBase64 = Base64.getEncoder().encodeToString(payload);
		this.qos = qos;
	}

	/** 还原原始报文载荷（base64 解码） */
	public byte[] payload() {
		return Base64.getDecoder().decode(payloadBase64);
	}

}
