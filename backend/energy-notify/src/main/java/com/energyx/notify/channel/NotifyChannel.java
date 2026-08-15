package com.energyx.notify.channel;

/**
 * 通知渠道枚举。
 *
 * <p>
 * 与 iot_notify_config.channel / iot_notify_template.channel 取值一致；
 * 短信/语音渠道预留（SMS/VOICE），接入供应商 SDK 后在此扩展执行器。
 * </p>
 */
public enum NotifyChannel {

	/** 通用 HTTP Webhook */
	WEBHOOK("WEBHOOK", "Webhook"),

	/** 企业微信群机器人 */
	WECOM("WECOM", "企业微信"),

	/** 钉钉群机器人 */
	DINGTALK("DINGTALK", "钉钉"),

	/** 邮件（SMTP） */
	EMAIL("EMAIL", "邮件"),

	/** 短信（预留） */
	SMS("SMS", "短信"),

	/** 语音（预留） */
	VOICE("VOICE", "语音");

	private final String code;

	private final String label;

	NotifyChannel(String code, String label) {
		this.code = code;
		this.label = label;
	}

	public String getCode() {
		return code;
	}

	public String getLabel() {
		return label;
	}

	/** 是否已实现发送能力（SMS/VOICE 预留未实现，发送时提示渠道未配置） */
	public boolean supported() {
		return this == WEBHOOK || this == WECOM || this == DINGTALK || this == EMAIL;
	}

}
