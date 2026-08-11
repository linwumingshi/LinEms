package com.energyx.ems.entity;

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
@TableName("ems_demand_config")
public class EmsDemandConfig {

	@TableId(type = IdType.AUTO)
	private Long demandConfigId;

	private Long tenantId;

	private Long stationId;

	/** 需量限值 kW（>0 启用检测） */
	private BigDecimal demandLimitKw;

	/** 需量费率 ¥/kW·月 */
	private BigDecimal demandRate;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
