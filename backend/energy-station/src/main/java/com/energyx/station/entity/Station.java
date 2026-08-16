package com.energyx.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.GridType;
import com.energyx.common.enums.StationStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 电站资产（iot_station）。
 *
 * <p>
 * 电站为资产树根（无 MQTT 接入），下属设备通过 device.station_id 归属。 tenant_id 不显式赋值：插入时由条件化租户拦截器注入。
 * </p>
 */
@Getter
@Setter
@TableName("iot_station")
public class Station extends BaseEntity {

	/** 电站主键 ID（自增，对应 iot_station.station_id）。 */
	@TableId(type = IdType.AUTO)
	private Long stationId;

	/** 所属企业 ID（iot_station.enterprise_id NOT NULL，必填）。 */
	private Long enterpriseId;

	/** 电站编码（唯一业务编码，最大长度 64）。 */
	private String stationCode;

	/** 电站名称（最大长度 128）。 */
	private String stationName;

	/** 电站地址（最大长度 256）。 */
	private String address;

	/** 经度（单位：度，取值范围 -180 ~ 180）。 */
	private BigDecimal longitude;

	/** 纬度（单位：度，取值范围 -90 ~ 90）。 */
	private BigDecimal latitude;

	/** 装机容量（单位：kWh）。 */
	private BigDecimal installCapacity;

	/** PCS 容量（单位：kW）。 */
	private BigDecimal pcsCapacity;

	/** 电池容量（单位：kWh）。 */
	private BigDecimal batteryCapacity;

	/** 电网类型，取值见 {@link com.energyx.common.enums.GridType}（工商业/园区/电网侧）。 */
	private GridType gridType;

	/** 电站运行状态，取值见 {@link com.energyx.common.enums.StationStatus}（0 停运 / 1 运行）。 */
	private StationStatus status;

}
