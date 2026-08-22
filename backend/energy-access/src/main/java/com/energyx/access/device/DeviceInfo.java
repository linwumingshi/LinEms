package com.energyx.access.device;

import lombok.Data;

/**
 * 设备上下文快照（缓存 JSON 载体）。 字段与 DeviceMapper 投影列一致，供标准化消息携带 tenant/station/enterprise 维度。
 */
@Data
public class DeviceInfo {

	/** 设备 ID（iot_device 主键，标准化消息与 Kafka key 的统一标识） */
	private Long deviceId;

	/** 租户 ID（多租户隔离维度） */
	private Long tenantId;

	/** 企业 ID（集团/企业维度） */
	private Long enterpriseId;

	/** 站点 ID（储能站维度，用于站点级聚合） */
	private Long stationId;

	/** 设备类型 */
	private String deviceType;

	/** 设备在线状态（2 离线 / 3 在线 / 5 封禁等，对应 DeviceStatus 枚举） */
	private Integer status;

}
