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

	/**
	 * 登录用户名，与 tenantId 组成复合登录标识（sys_user 唯一键为 tenant_id + username）。前后空白会被裁剪。
	 *
	 * @required
	 */
	@NotBlank(message = "用户名不能为空")
	private String username;

	/**
	 * 登录密码明文，服务端与 BCrypt 哈希比对，不落日志。
	 *
	 * @required
	 */
	@NotBlank(message = "密码不能为空")
	private String password;

	/**
	 * 租户 ID。为空或小于等于 0 时按默认租户 1（EnergyX）处理，多租户场景由前端显式传入。
	 */
	private Long tenantId;

}
