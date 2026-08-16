package com.energyx.device.web.dto;

import lombok.Data;

/**
 * 设备分页查询条件（GET query 参数）。 租户范围由 {@link com.energyx.common.tenant.TenantContext}
 * 注入，请求参数无需带 tenantId。
 */
@Data
public class DeviceQuery {

	/** 页码，从 1 开始，默认 1 */
	private Integer pageNum = 1;

	/** 每页条数，默认 20 */
	private Integer pageSize = 20;

	/** 所属电站 ID 筛选 */
	private Long stationId;

	/**
	 * 设备类型筛选（字符串，如 ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER），见
	 * {@link com.energyx.common.enums.DeviceType}
	 */
	private String deviceType;

	/** 父设备 ID 筛选（按父节点查子设备） */
	private Long parentId;

	/**
	 * 设备生命周期状态筛选（0未注册 1未激活 2已激活 3在线 4禁用 5封禁），见
	 * {@link com.energyx.common.enums.DeviceStatus}
	 */
	private Integer status;

	/** 设备名模糊匹配 */
	private String keyword;

	/** 所属企业 ID 筛选 */
	private Long enterpriseId;

	/** 产品标识过滤（前端产品联动选择） */
	private String productKey;

}
