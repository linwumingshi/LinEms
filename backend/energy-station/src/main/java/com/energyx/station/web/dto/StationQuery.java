package com.energyx.station.web.dto;

import lombok.Data;

/**
 * 电站分页查询条件（GET query 参数）。
 * 租户范围由 {@link com.energyx.common.tenant.TenantContext} 注入。
 */
@Data
public class StationQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private Long enterpriseId;

    /** 名称/编码模糊匹配 */
    private String keyword;

    private Integer status;

    private String gridType;
}
