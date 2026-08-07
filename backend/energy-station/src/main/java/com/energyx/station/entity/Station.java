package com.energyx.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 电站资产（iot_station）。
 *
 * <p>电站为资产树根（无 MQTT 接入），下属设备通过 device.station_id 归属。
 * tenant_id 不显式赋值：插入时由条件化租户拦截器注入。</p>
 */
@Getter
@Setter
@TableName("iot_station")
public class Station extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long stationId;

    private Long tenantId;

    /** 所属企业（必填） */
    private Long enterpriseId;

    private String stationCode;

    private String stationName;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    /** 装机容量 kWh */
    private BigDecimal installCapacity;

    /** PCS 容量 kW */
    private BigDecimal pcsCapacity;

    /** 电池容量 kWh */
    private BigDecimal batteryCapacity;

    /** 工商业/园区/电网侧 */
    private String gridType;

    /** 0停运 1运行 */
    private Integer status;
}
