package com.sanduo.energy.device.web.dto;

import lombok.Data;

/**
 * 设备分页查询条件（GET query 参数）。
 * 租户范围由 {@link com.sanduo.energy.common.tenant.TenantContext} 注入，请求参数无需带 tenantId。
 */
@Data
public class DeviceQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private Long stationId;

    private String deviceType;

    private Long parentId;

    private Integer status;

    /** 设备名模糊匹配 */
    private String keyword;

    private Long enterpriseId;
}
