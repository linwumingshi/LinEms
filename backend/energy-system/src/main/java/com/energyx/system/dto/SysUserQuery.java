package com.energyx.system.dto;

import lombok.Data;

/**
 * 用户分页查询条件。
 */
@Data
public class SysUserQuery {

	/**
	 * 当前页码，从 1 开始，默认 1；小于 1 时按 1 处理。
	 */
	private long current = 1;

	/**
	 * 每页大小，默认 10；小于等于 0 时按 10 处理，上限 100（超出按 100 截断）。
	 */
	private long size = 10;

	/**
	 * 关键字，对用户名与真实姓名做「或」关系模糊匹配（前后空白裁剪）；为空表示不限。
	 */
	private String keyword;

	/**
	 * 用户状态过滤码，取值 0 禁用 / 1 启用 / 2 锁定，语义见
	 * {@link com.energyx.common.enums.UserStatus}；为空表示不限。
	 */
	private Integer status;

	/**
	 * 所属单位 ID 过滤，仅精确匹配该单位本级（不含子单位）；为空表示不限。
	 */
	private Long enterpriseId;

}
