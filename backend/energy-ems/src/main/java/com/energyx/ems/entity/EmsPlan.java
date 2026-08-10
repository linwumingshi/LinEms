package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 策略计划头（ems_plan）。充放电点序列入 TDengine，本表存计划元数据。 */
@Data
@TableName("ems_plan")
public class EmsPlan {

	@TableId(type = IdType.AUTO)
	private Long planId;

	private Long tenantId;

	private Long stationId;

	private Long strategyId;

	private LocalDate planDate;

	/** 1充电 2放电 3混合 */
	private Integer planType;

	private BigDecimal totalEnergy;

	/** 计划参数快照 JSON */
	private String planParam;

	/** 0待执行 1执行中 2完成 3已取消 */
	private Integer status;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
