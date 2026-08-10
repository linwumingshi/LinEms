package com.energyx.device.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 设备凭据视图。deviceSecret 仅创建/重生成时返回明文，查询返回脱敏。
 */
@Data
@AllArgsConstructor
public class CredentialView {

	private Long deviceId;

	private String deviceName;

	private String deviceSecret;

	/** 1正常 2吊销 */
	private Integer authStatus;

}
