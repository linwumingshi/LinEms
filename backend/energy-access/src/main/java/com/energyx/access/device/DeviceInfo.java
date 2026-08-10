package com.energyx.access.device;

import lombok.Data;

/**
 * 设备上下文快照（缓存 JSON 载体）。 字段与 DeviceMapper 投影列一致，供标准化消息携带 tenant/station/enterprise 维度。
 */
@Data
public class DeviceInfo {

	private Long deviceId;

	private Long tenantId;

	private Long enterpriseId;

	private Long stationId;

	private String deviceType;

	private Integer status;

}
