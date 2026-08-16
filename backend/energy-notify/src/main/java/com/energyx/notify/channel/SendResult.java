package com.energyx.notify.channel;

/**
 * 渠道发送结果（成功/失败 + 说明，供执行日志与调用方展示）。
 *
 * @param success 是否发送成功
 * @param message 结果说明（成功细节或失败原因）
 */
public record SendResult(boolean success, String message) {

	public static SendResult ok(String message) {
		return new SendResult(true, message);
	}

	public static SendResult fail(String message) {
		return new SendResult(false, message);
	}

}
