package com.energyx.ems.entity;

import com.energyx.common.entity.BaseEntity;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.PlanStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 策略计划头（ems_plan）。充放电点序列入 TDengine，本表存计划元数据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ems_plan")
public class EmsPlan extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long planId;

	private Long stationId;

	private Long strategyId;

	private LocalDate planDate;

	/** 1充电 2放电 3混合 */
	private Integer planType;

	private BigDecimal totalEnergy;

	/** 计划参数快照 JSON */
	private String planParam;

	/** 计划状态（PENDING/RUNNING/COMPLETED/CANCELED/FAILED） */
	private PlanStatus status;

}
