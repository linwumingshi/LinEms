package com.energyx.ota.client.dto;

import lombok.Data;

/**
 * 设备查询条件（对齐 device 服务 DeviceQuery 的 JSON 字段）。
 */
@Data
public class DeviceQuery {

	private Integer pageNum = 1;

	private Integer pageSize = 500;

	private Long stationId;

	private String deviceType;

	private Long parentId;

	private Integer status;

	private String keyword;

	private Long enterpriseId;

	private String productKey;

}
