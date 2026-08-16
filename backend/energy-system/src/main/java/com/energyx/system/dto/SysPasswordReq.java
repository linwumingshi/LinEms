package com.energyx.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求。
 */
@Data
public class SysPasswordReq {

	/**
	 * 新登录密码明文，长度需在 6~64 位。服务端加密后写库，并吊销该用户全部在线会话。
	 *
	 */
	@NotBlank(message = "新密码不能为空")
	@Size(min = 6, max = 64, message = "密码长度需在 6~64 位")
	private String password;

}
