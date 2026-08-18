package com.energyx.mock.client.dto;

import lombok.Data;

/**
 * 设备创建请求（energy-device DeviceCreateReq 副本，仅含自动建档所需字段）。 deviceType 用枚举名字符串（如 PCS /
 * EDGE_GW），由 energy-device 反序列化为 DeviceType 枚举。
 */
@Data
public class DeviceCreateReq {

	/** 设备名（禁止 _ 与 &；模拟器会自动将 _ 替换为 -） */
	private String deviceName;

	/** 设备类型枚举名（ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER） */
	private String deviceType;

	/** 产品标识（认证与路由锚点） */
	private String productKey;

	/** 所属电站 ID（可选） */
	private Long stationId;

	/** 所属企业 ID（可选） */
	private Long enterpriseId;

	/** 初始固件版本（可选，用于 OTA 仿真基线） */
	private String firmwareVersion;

	/** 接入协议，默认 MQTT */
	private String protocol;

}
