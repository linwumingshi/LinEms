package com.energyx.product.web.dto;

import lombok.Data;

/**
 * 产品分页查询条件（GET query 参数）。 租户范围由 {@link com.energyx.common.tenant.TenantContext} 注入。
 */
@Data
public class ProductQuery {

	/** 页码，从 1 开始，默认 1 */
	private Integer pageNum = 1;

	/** 每页条数，默认 20 */
	private Integer pageSize = 20;

	/** 设备类型筛选（精确匹配），如 ENERGY_CABINET/PCS/BMS 等 */
	private String deviceType;

	/** 产品名/产品标识模糊匹配 */
	private String keyword;

	/** 产品状态筛选，见 {@link com.energyx.common.enums.ProductStatus}（0 禁用 1 启用） */
	private Integer status;

}
