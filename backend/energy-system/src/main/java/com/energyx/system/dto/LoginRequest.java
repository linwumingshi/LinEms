package com.energyx.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求。 tenantId 可选：默认租户 1（EnergyX）。多租户场景前端显式传入。
 */
@Getter
@Setter
public class LoginRequest {

	@NotBlank(message = "用户名不能为空")
	private String username;

	@NotBlank(message = "密码不能为空")
	private String password;

	/** 租户 ID，缺省 1 */
	private Long tenantId;

}
