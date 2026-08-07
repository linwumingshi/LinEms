package com.sanduo.energy.product.web.dto;

import lombok.Data;

/**
 * 产品分页查询条件（GET query 参数）。
 * 租户范围由 {@link com.sanduo.energy.common.tenant.TenantContext} 注入。
 */
@Data
public class ProductQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private String deviceType;

    /** 产品名/产品标识模糊匹配 */
    private String keyword;

    private Integer status;
}
