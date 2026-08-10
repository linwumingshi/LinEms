package com.energyx.broker.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 签名器（纯函数，可单测）。
 *
 * <p>
 * 认证签名规范（Phase 1 §4.8 / ADR-011）： <pre>
 *   sign = HMAC-SHA256(deviceSecret, clientId & timestamp & nonce)
 *   password  = sign.toLowerHex()
 * </pre> timestamp 为毫秒，nonce 为客户端随机串；服务端校验 timestamp ∈ [now-2min, now+2min]， nonce 经
 * Redis SETNX 一次性消费（mqtt:nonce:*，5min TTL）防重放。
 * </p>
 */
public final class HmacSigner {

	private static final String HMAC_SHA256 = "HmacSHA256";

	private HmacSigner() {
	}

	/** 计算标准签名串的 HMAC-SHA256，返回 64 位小写 hex */
	public static String sign(String deviceSecret, String clientId, String timestamp, String nonce) {
		return hmacSha256Hex(deviceSecret, clientId + "&" + timestamp + "&" + nonce);
	}

	public static String hmacSha256Hex(String key, String message) {
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256);
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException("HMAC-SHA256 计算失败", e);
		}
	}

	/** 常数时间比较，防时序侧信道 */
	public static boolean constantTimeEquals(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

}
