package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需量检测槽位记录（ems_demand_record）。每站每 15min 槽位一条（uk_demand_record_window）。 */
@Data
@TableName("ems_demand_record")
public class EmsDemandRecord {

	@TableId(type = IdType.AUTO)
	private Long demandRecordId;

	private Long tenantId;

	private Long stationId;

	/** 槽位起点 */
	private LocalDateTime windowStart;

	/** 槽位终点 */
	private LocalDateTime windowEnd;

	/** 槽位实际需量（15min 平均功率 kW） */
	private BigDecimal demandKw;

	/** 限值快照 kW */
	private BigDecimal limitKw;

	/** 是否超限 */
	private Boolean overLimit;

	/** 削峰放电功率 kW（未削峰=0） */
	private BigDecimal shavedKw;

	/** NONE/SHED/SHED_FAILED/ALARM_ONLY */
	private String action;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

}
