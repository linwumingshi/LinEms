package com.energyx.ems.entity;

import com.energyx.common.entity.BaseEntity;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需量管理站点配置（ems_demand_config）。tenant+station 唯一（uk_demand_config_station）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ems_demand_config")
public class EmsDemandConfig extends BaseEntity {

	/** 需量配置 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long demandConfigId;

	/** 站点 ID（tenant+station 唯一） */
	private Long stationId;

	/** 需量限值（kW）；> 0 时启用检测 */
	private BigDecimal demandLimitKw;

	/** 需量费率（¥/kW·月） */
	private BigDecimal demandRate;

}
