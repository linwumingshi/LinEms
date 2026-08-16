package com.energyx.system.dto;

import com.energyx.common.enums.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户创建/更新请求。
 * <p>
 * password 仅在创建时必填；更新时留空表示不修改密码。
 * </p>
 */
@Data
public class SysUserSaveReq {

	/**
	 * 登录用户名，同租户内唯一。最大长度 64，仅允许字母、数字、下划线、点、短横线。
	 *
	 */
	@NotBlank(message = "用户名不能为空")
	@Size(max = 64, message = "用户名长度不能超过 64")
	@Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "用户名仅允许字母、数字、下划线、点、短横线")
	private String username;

	/**
	 * 用户真实姓名，最大长度 64。
	 *
	 */
	@NotBlank(message = "姓名不能为空")
	@Size(max = 64, message = "姓名长度不能超过 64")
	private String realName;

	/**
	 * 联系手机号，最大长度 32；可空。
	 */
	@Size(max = 32, message = "手机号长度不能超过 32")
	private String phone;

	/**
	 * 联系邮箱，最大长度 128；可空。
	 */
	@Size(max = 128, message = "邮箱长度不能超过 128")
	private String email;

	/**
	 * 所属单位（组织）ID，取值来自 {@code GET /system/enterprise/list}；可空表示不归属任何单位。
	 */
	private Long enterpriseId;

	/**
	 * 用户状态。JSON 传状态码：0 禁用 / 1 启用 / 2 锁定，枚举常量见 {@link com.energyx.common.enums.UserStatus}
	 * （DISABLED/ENABLED/LOCKED）。创建时为空默认启用。
	 */
	private UserStatus status;

	/**
	 * 登录密码明文，长度需在 6~64 位。创建用户时必填；更新用户时留空表示不修改密码，传值则重新加密并吊销该用户在线会话。
	 */
	@Size(min = 6, max = 64, message = "密码长度需在 6~64 位")
	private String password;

	/**
	 * 分配的角色 ID 集合，全量覆盖语义（重复项自动去重），所有 ID 必须存在。
	 * <p>
	 * {@code null} 表示不修改角色绑定；空数组表示清空全部角色。
	 * </p>
	 */
	private List<Long> roleIds;

}
