package com.energyx.ota.util;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OTA 安全工具（S5）：SHA256 摘要、RSA 签名/验签（SHA256withRSA）、URL 时效签名（HMAC-SHA256）。
 *
 * <p>
 * RSA 密钥对可预生成（genKeyPair）或从 Base64 字符串还原；URL 签名用于下载链接 时效校验（expires + HMAC），设备/管理端拿到链接后 N
 * 秒内有效。
 * </p>
 */
public final class OtaCryptoUtil {

	private OtaCryptoUtil() {
	}

	/** SHA-256 摘要（hex） */
	public static String sha256(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(data));
		}
		catch (NoSuchAlgorithmException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SHA-256 不可用");
		}
	}

	/** MD5 摘要（hex，32 位小写） */
	public static String md5(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(md.digest(data));
		}
		catch (NoSuchAlgorithmException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MD5 不可用");
		}
	}

	/** SHA-256 摘要（hex，流式） */
	public static String sha256(MessageDigest md, byte[] data) {
		md.update(data);
		return HexFormat.of().formatHex(md.digest());
	}

	/** 生成 RSA 密钥对（2048 位） */
	public static KeyPair genKeyPair() {
		try {
			KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
			gen.initialize(2048);
			return gen.generateKeyPair();
		}
		catch (NoSuchAlgorithmException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "RSA 不可用");
		}
	}

	/** 私钥 → Base64（PKCS8） */
	public static String encodePrivateKey(PrivateKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	/** 公钥 → Base64（X509） */
	public static String encodePublicKey(PublicKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	/** Base64 私钥 → PrivateKey */
	public static PrivateKey decodePrivateKey(String base64) {
		try {
			byte[] der = Base64.getDecoder().decode(base64);
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "私钥格式错误");
		}
	}

	/** Base64 公钥 → PublicKey */
	public static PublicKey decodePublicKey(String base64) {
		try {
			byte[] der = Base64.getDecoder().decode(base64);
			return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "公钥格式错误");
		}
	}

	/** 用私钥对数据签名（SHA256withRSA），返回 Base64 */
	public static String sign(PrivateKey key, byte[] data) {
		try {
			Signature sig = Signature.getInstance("SHA256withRSA");
			sig.initSign(key);
			sig.update(data);
			return Base64.getEncoder().encodeToString(sig.sign());
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "签名失败");
		}
	}

	/** 用公钥验签，返回是否通过 */
	public static boolean verify(PublicKey key, byte[] data, String signatureBase64) {
		try {
			Signature sig = Signature.getInstance("SHA256withRSA");
			sig.initVerify(key);
			sig.update(data);
			return sig.verify(Base64.getDecoder().decode(signatureBase64));
		}
		catch (Exception e) {
			return false;
		}
	}

	/** HMAC-SHA256 签名（hex），URL 时效签名使用 */
	public static String hmac(String secret, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HMAC 不可用");
		}
	}

}
