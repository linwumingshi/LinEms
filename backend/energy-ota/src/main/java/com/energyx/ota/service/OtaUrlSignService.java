package com.energyx.ota.service;

import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.util.OtaCryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 升级包签名下载 URL（S5-4，HTTPS 签名 URL）。
 *
 * <p>
 * 下发信封中的下载地址携带时效签名：{baseUrl}/{objectKey}?expires={epoch}&sign={hmac}， hmac =
 * HMAC-SHA256(secret, "GET{objectKey}{expires}")。下载接口校验时间窗与签名， 过期/篡改一律
 * 403——杜绝中间人替换下载地址指向恶意固件。
 * </p>
 */
@Slf4j
@Service
public class OtaUrlSignService {

	private final OtaProperties props;

	public OtaUrlSignService(OtaProperties props) {
		this.props = props;
	}

	/**
	 * 生成带时效签名的下载 URL。
	 * @param objectKey 存储相对路径（{productKey}/{version}/{module}/{fileName}）
	 * @return 完整下载 URL（含 expires/sign 参数）
	 */
	public String signUrl(String objectKey) {
		long expires = System.currentTimeMillis() / 1000 + props.getUrlExpireSeconds();
		String sign = sign(objectKey, expires);
		return props.getDownloadBaseUrl() + "/" + objectKey + "?expires=" + expires + "&sign=" + sign;
	}

	/** 校验下载 URL 签名（expires 过期或 sign 不匹配 → false） */
	public boolean verify(String objectKey, long expires, String sign) {
		long now = System.currentTimeMillis() / 1000;
		if (expires < now) {
			return false;
		}
		return OtaCryptoUtil.hmac(props.getUrlSecret(), "GET" + objectKey + expires).equalsIgnoreCase(sign);
	}

	/** 计算签名 */
	private String sign(String objectKey, long expires) {
		return OtaCryptoUtil.hmac(props.getUrlSecret(), "GET" + objectKey + expires);
	}

}
