package com.energyx.device.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 设备凭据视图。deviceSecret 仅创建/重生成时返回明文，查询返回脱敏。
 */
@Data
@AllArgsConstructor
public class CredentialView {

	/** 设备主键 */
	private Long deviceId;

	/** 设备名 */
	private String deviceName;

	/** 设备密钥（HMAC 签名用）；仅创建/重新生成时返回明文，查询返回脱敏（null 或掩码） */
	private String deviceSecret;

	/** 凭据认证状态：1正常 2吊销，见 {@link com.energyx.common.enums.CredentialAuthStatus} */
	private Integer authStatus;

}
