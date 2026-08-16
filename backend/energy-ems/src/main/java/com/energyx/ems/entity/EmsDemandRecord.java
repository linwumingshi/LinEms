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

	/** 租户 ID */
	private Long tenantId;

	/** 创建时间（DB DEFAULT CURRENT_TIMESTAMP） */
	private LocalDateTime createTime;

	/** 需量记录 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long demandRecordId;

	/** 站点 ID */
	private Long stationId;

	/** 槽位起点（每 15min 一个槽位） */
	private LocalDateTime windowStart;

	/** 槽位终点 */
	private LocalDateTime windowEnd;

	/** 槽位实际需量（15min 平均功率，kW） */
	private BigDecimal demandKw;

	/** 限值快照（kW） */
	private BigDecimal limitKw;

	/** 是否超限 */
	private Boolean overLimit;

	/** 削峰放电功率（kW）；未削峰为 0 */
	private BigDecimal shavedKw;

	/** 采取的削峰动作；NONE=无，SHED=已削峰，SHED_FAILED=削峰失败，ALARM_ONLY=仅告警 */
	private String action;

}
