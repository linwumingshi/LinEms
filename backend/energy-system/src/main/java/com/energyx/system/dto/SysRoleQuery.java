package com.energyx.system.dto;

import lombok.Data;

/**
 * 角色分页查询条件。
 */
@Data
public class SysRoleQuery {

	/**
	 * 当前页码，从 1 开始，默认 1；小于 1 时按 1 处理。
	 */
	private long current = 1;

	/**
	 * 每页大小，默认 10；小于等于 0 时按 10 处理，上限 100（超出按 100 截断）。
	 */
	private long size = 10;

	/**
	 * 关键字，对角色编码与角色名称做「或」关系模糊匹配（前后空白裁剪）；为空表示不限。
	 */
	private String keyword;

	/**
	 * 角色状态过滤码，取值 0 禁用 / 1 启用，语义见 {@link com.energyx.common.enums.RoleStatus}；为空表示不限。
	 */
	private Integer status;

}
