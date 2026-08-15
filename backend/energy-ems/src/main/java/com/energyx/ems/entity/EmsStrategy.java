package com.energyx.ems.entity;

import com.energyx.common.entity.BaseEntity;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.StrategyStatus;
import com.energyx.common.enums.StrategyType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略定义（ems_strategy）。继承 {@link BaseEntity}：表含 tenant_id/create_by/create_time/
 * update_time/deleted 列（deleted 已由迁移脚本补充），审计字段与逻辑删除统一由基类承载。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ems_strategy")
public class EmsStrategy extends BaseEntity {

	@TableId(type = IdType.AUTO)
	private Long strategyId;

	private Long stationId;

	private String strategyName;

	/** 策略类型（PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME） */
	private StrategyType strategyType;

	/** 策略配置 JSON（chargeWindows/dischargeWindows/socRange） */
	private String config;

	/** 多策略冲突仲裁优先级 */
	private Integer priority;

	/** 策略状态（DRAFT/ENABLED/DISABLED） */
	private StrategyStatus status;

	private Integer version;

}
