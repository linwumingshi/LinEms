package com.energyx.station.web.dto;

import lombok.Data;

/**
 * 电站分页查询条件（GET query 参数）。 租户范围由 {@link com.energyx.common.tenant.TenantContext} 注入。
 */
@Data
public class StationQuery {

	/** 页码，从 1 开始，默认 1。 */
	private Integer pageNum = 1;

	/** 每页记录数，默认 20。 */
	private Integer pageSize = 20;

	/** 按所属企业 ID 筛选（可选）。 */
	private Long enterpriseId;

	/** 名称/编码模糊匹配关键字（可选）。 */
	private String keyword;

	/**
	 * 按运行状态筛选（可选），取值对应 {@link com.energyx.common.enums.StationStatus} 的 code：0 停运 / 1 运行。
	 */
	private Integer status;

	/**
	 * 按电网类型筛选（可选），取值为 {@link com.energyx.common.enums.GridType} 的 code 中文原值：工商业 / 园区 /
	 * 电网侧。
	 */
	private String gridType;

}
