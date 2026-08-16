package com.energyx.ota.client.dto;

import lombok.Data;

/**
 * 设备更新请求（对齐 device 服务 DeviceUpdateReq 的 JSON 字段，仅取 OTA 回写所需）。
 */
@Data
public class DeviceUpdateReq {

	private String deviceName;

	private String deviceType;

	private Long stationId;

	/** 固件版本（升级成功回写） */
	private String firmwareVersion;

	private String mac;

	private String ip;

	private Integer sort;

}
