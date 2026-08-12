package com.energyx.station.web.dto;

import com.energyx.common.enums.GridType;
import com.energyx.common.enums.StationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 电站创建/更新请求。
 */
@Data
public class StationSaveReq {

	@NotBlank(message = "电站编码不能为空")
	@Size(max = 64, message = "电站编码长度不能超过 64")
	private String stationCode;

	@NotBlank(message = "电站名称不能为空")
	@Size(max = 128, message = "电站名称长度不能超过 128")
	private String stationName;

	/** 所属企业（iot_station.enterprise_id NOT NULL） */
	@NotNull(message = "所属企业不能为空")
	private Long enterpriseId;

	@Size(max = 256, message = "地址长度不能超过 256")
	private String address;

	private BigDecimal longitude;

	private BigDecimal latitude;

	/** 装机容量 kWh */
	private BigDecimal installCapacity;

	/** PCS 容量 kW */
	private BigDecimal pcsCapacity;

	/** 电池容量 kWh */
	private BigDecimal batteryCapacity;

	/** 电网类型（COMMERCIAL_INDUSTRIAL/PARK/GRID，对应 DB 中文原值 工商业/园区/电网侧） */
	private GridType gridType;

	/** 默认 1 运行 */
	private StationStatus status;

}
