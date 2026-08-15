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

	@TableId(type = IdType.AUTO)
	private Long constraintId;

	private Long stationId;

	private BigDecimal socMin;

	private BigDecimal socMax;

	private BigDecimal chargePowerMax;

	private BigDecimal dischargePowerMax;

	private BigDecimal tempMax;

	private BigDecimal voltageMax;

	private BigDecimal currentMax;

	/** 扩展安全包络 JSON */
	private String safetyEnvelope;

	/** 约束状态（DISABLED/ENABLED） */
	private ConstraintStatus status;

}
