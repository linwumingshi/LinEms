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

	/**
	 * 电站编码（唯一业务编码，最大长度 64）。
	 *
	 * @required
	 */
	@NotBlank(message = "电站编码不能为空")
	@Size(max = 64, message = "电站编码长度不能超过 64")
	private String stationCode;

	/**
	 * 电站名称（最大长度 128）。
	 *
	 * @required
	 */
	@NotBlank(message = "电站名称不能为空")
	@Size(max = 128, message = "电站名称长度不能超过 128")
	private String stationName;

	/**
	 * 所属企业 ID（iot_station.enterprise_id NOT NULL）。
	 *
	 * @required
	 */
	@NotNull(message = "所属企业不能为空")
	private Long enterpriseId;

	/** 电站地址（可选，最大长度 256）。 */
	@Size(max = 256, message = "地址长度不能超过 256")
	private String address;

	/** 经度（可选，单位：度，取值范围 -180 ~ 180）。 */
	private BigDecimal longitude;

	/** 纬度（可选，单位：度，取值范围 -90 ~ 90）。 */
	private BigDecimal latitude;

	/** 装机容量（可选，单位：kWh）。 */
	private BigDecimal installCapacity;

	/** PCS 容量（可选，单位：kW）。 */
	private BigDecimal pcsCapacity;

	/** 电池容量（可选，单位：kWh）。 */
	private BigDecimal batteryCapacity;

	/** 电网类型（可选），取值见 {@link com.energyx.common.enums.GridType}（工商业/园区/电网侧）。 */
	private GridType gridType;

	/**
	 * 电站运行状态（可选，默认 1 运行），取值见 {@link com.energyx.common.enums.StationStatus}（0 停运 / 1 运行）。
	 */
	private StationStatus status;

}
