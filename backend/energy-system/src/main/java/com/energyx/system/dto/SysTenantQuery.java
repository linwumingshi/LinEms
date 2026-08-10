package com.energyx.system.dto;

import lombok.Data;

/**
 * 租户分页查询条件。
 */
@Data
public class SysTenantQuery {

	private long current = 1;

	private long size = 10;

	/** 编码/名称模糊搜索 */
	private String keyword;

}
