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

	/** 计划 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long planId;

	/** 站点 ID */
	private Long stationId;

	/** 引用策略 ID */
	private Long strategyId;

	/** 计划日期 */
	private LocalDate planDate;

	/** 计划类型；1=充电，2=放电，3=混合 */
	private Integer planType;

	/** 计划总电量（kWh） */
	private BigDecimal totalEnergy;

	/** 计划参数快照 JSON */
	private String planParam;

	/** 计划状态，取值见 {@link PlanStatus}（PENDING/RUNNING/COMPLETED/CANCELED/FAILED） */
	private PlanStatus status;

}
