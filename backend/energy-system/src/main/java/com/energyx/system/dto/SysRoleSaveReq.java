package com.energyx.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 角色创建/更新请求。
 */
@Data
public class SysRoleSaveReq {

	@NotBlank(message = "角色编码不能为空")
	@Size(max = 64, message = "角色编码长度不能超过 64")
	@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "角色编码需以字母开头，仅允许字母、数字、下划线")
	private String roleCode;

	@NotBlank(message = "角色名称不能为空")
	@Size(max = 64, message = "角色名称长度不能超过 64")
	private String roleName;

	/** 数据范围：1 本人 2 本企业 3 本租户 4 全部 */
	private Integer dataScope;

	/** 默认启用 */
	private Integer status;

}
