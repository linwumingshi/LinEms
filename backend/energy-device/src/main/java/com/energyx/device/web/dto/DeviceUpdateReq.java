package com.energyx.device.web.dto;

import com.energyx.common.enums.DeviceType;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备更新请求（仅更新非空字段）。
 */
@Data
public class DeviceUpdateReq {

	@Size(max = 128, message = "设备名长度不能超过 128")
	private String deviceName;

	/** 设备类型（ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER），可空 */
	private DeviceType deviceType;

	private Long stationId;

	private String firmwareVersion;

	private String mac;

	private String ip;

	private Integer sort;

}
