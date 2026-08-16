package com.energyx.system.dto;

import com.energyx.common.enums.DataScope;
import com.energyx.common.enums.RoleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 角色创建/更新请求。
 */
@Data
public class SysRoleSaveReq {

	/**
	 * 角色编码，同租户内唯一，最大长度 64，需以字母开头且仅允许字母、数字、下划线，如 {@code SUPER_ADMIN}、
	 * {@code OPERATOR}。该编码用于会话权限刷新定位，变更后会重新校验唯一性。
	 *
	 */
	@NotBlank(message = "角色编码不能为空")
	@Size(max = 64, message = "角色编码长度不能超过 64")
	@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "角色编码需以字母开头，仅允许字母、数字、下划线")
	private String roleCode;

	/**
	 * 角色名称（展示用），最大长度 64。
	 *
	 */
	@NotBlank(message = "角色名称不能为空")
	@Size(max = 64, message = "角色名称长度不能超过 64")
	private String roleName;

	/**
	 * 数据可见范围。JSON 传状态码：1 本人 / 2 本企业 / 3 本租户 / 4 全部，枚举常量见
	 * {@link com.energyx.common.enums.DataScope}（SELF/ENTERPRISE/TENANT/ALL）。 创建时为空默认
	 * {@link com.energyx.common.enums.DataScope#TENANT}（本租户）。
	 */
	private DataScope dataScope;

	/**
	 * 角色状态。JSON 传状态码：0 禁用 / 1 启用，枚举常量见
	 * {@link com.energyx.common.enums.RoleStatus}（DISABLED/ENABLED）。创建时为空默认启用。
	 */
	private RoleStatus status;

}
