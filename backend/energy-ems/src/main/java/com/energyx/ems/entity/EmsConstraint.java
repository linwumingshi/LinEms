package com.energyx.ems.entity;

import com.energyx.common.entity.BaseEntity;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.ConstraintStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 安全约束（ems_constraint）。下发前安全包络校验，Phase1 §2.4。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ems_constraint")
public class EmsConstraint extends BaseEntity {

	/** 约束 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long constraintId;

	/** 站点 ID */
	private Long stationId;

	/** SOC 下限（%） */
	private BigDecimal socMin;

	/** SOC 上限（%） */
	private BigDecimal socMax;

	/** 最大充电功率（kW） */
	private BigDecimal chargePowerMax;

	/** 最大放电功率（kW） */
	private BigDecimal dischargePowerMax;

	/** 最高温度（℃） */
	private BigDecimal tempMax;

	/** 最高电压（V） */
	private BigDecimal voltageMax;

	/** 最大电流（A） */
	private BigDecimal currentMax;

	/** 扩展安全包络 JSON */
	private String safetyEnvelope;

	/** 约束状态，取值见 {@link ConstraintStatus}（DISABLED/ENABLED） */
	private ConstraintStatus status;

}
