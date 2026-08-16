package com.energyx.ota.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.ota.util.OtaCryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * OTA 固件签名服务（S5-2，RSA-SHA256withRSA）。
 *
 * <p>
 * 上传升级包时用私钥对文件 SHA256 摘要签名（signature 字段），设备侧用预置公钥验签，
 * 验签通过才允许安装（防中间人篡改/伪造固件）。密钥对：默认首次启动自动生成并打印公钥；
 * 生产环境应通过配置注入私钥（ota.signature.private-key-base64）保持一致。
 * </p>
 */
@Slf4j
@Service
public class OtaSignService {

	private final PrivateKey privateKey;

	private final PublicKey publicKey;

	public OtaSignService(@Value("${energy.ota.signature.private-key-base64:}") String privateKeyBase64) {
		if (privateKeyBase64 != null && !privateKeyBase64.isBlank()) {
			this.privateKey = OtaCryptoUtil.decodePrivateKey(privateKeyBase64.trim());
			this.publicKey = OtaCryptoUtil
				.decodePublicKey(OtaCryptoUtil.encodePublicKey(derivePublic(this.privateKey)));
			int bits = ((java.security.interfaces.RSAKey) this.privateKey).getModulus().bitLength();
			log.info("[OTA] 使用配置注入的 RSA 私钥（{} 位）", bits);
		}
		else {
			KeyPair pair = OtaCryptoUtil.genKeyPair();
			this.privateKey = pair.getPrivate();
			this.publicKey = pair.getPublic();
			log.warn("[OTA] 未配置 RSA 私钥，启动自动生成（重启后签名会变化）——生产环境请配置 " + "energy.ota.signature.private-key-base64");
		}
		log.info("[OTA] 固件签名公钥 Base64: {}", OtaCryptoUtil.encodePublicKey(publicKey));
	}

	/** 从私钥推导公钥（X509 编码） */
	private static PublicKey derivePublic(PrivateKey privateKey) {
		try {
			java.security.spec.RSAPrivateCrtKeySpec spec = java.security.KeyFactory.getInstance("RSA")
				.getKeySpec(privateKey, java.security.spec.RSAPrivateCrtKeySpec.class);
			java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(spec.getModulus(),
					spec.getPublicExponent());
			return java.security.KeyFactory.getInstance("RSA").generatePublic(pubSpec);
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "私钥推导公钥失败");
		}
	}

	/** 对文件 SHA256 摘要签名（返回 Base64 签名） */
	public String signSha256(String fileSha256) {
		return OtaCryptoUtil.sign(privateKey, fileSha256.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** 验签：文件 SHA256 摘要 + 签名是否匹配 */
	public boolean verifySha256(String fileSha256, String signatureBase64) {
		if (signatureBase64 == null || signatureBase64.isBlank()) {
			return false;
		}
		return OtaCryptoUtil.verify(publicKey, fileSha256.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				signatureBase64);
	}

	/** 当前公钥 Base64（下发信封携带，设备侧验签） */
	public String publicKeyBase64() {
		return OtaCryptoUtil.encodePublicKey(publicKey);
	}

}
