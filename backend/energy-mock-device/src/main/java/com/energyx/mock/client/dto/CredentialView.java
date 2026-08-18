package com.energyx.mock.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备凭据视图（energy-device CredentialView 副本）。 deviceSecret 仅重新生成时返回明文，模拟器据此过 broker 鉴权。
 * 必须保留无参构造器：Feign 解码 Result<CredentialView> 时 Jackson 需要无参构造 + setter 反序列化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialView {

	/** 设备主键 */
	private Long deviceId;

	/** 设备名 */
	private String deviceName;

	/** 设备密钥（HMAC 签名用） */
	private String deviceSecret;

	/** 凭据认证状态：1正常 2吊销 */
	private Integer authStatus;

}
