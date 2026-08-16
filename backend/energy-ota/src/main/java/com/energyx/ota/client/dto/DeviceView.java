package com.energyx.ota.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 设备投影（对齐 device 服务 Device 实体 JSON 字段，仅取 OTA 所需；忽略未知字段）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceView {

	private Long deviceId;

	private Long tenantId;

	private Long enterpriseId;

	private Long stationId;

	private String productKey;

	private String deviceName;

	private String deviceType;

	private Integer status;

	private String firmwareVersion;

}
