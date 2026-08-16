package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.PlanPointState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** 策略执行记录（ems_execution_record）。execute_time 由 DB DEFAULT CURRENT_TIMESTAMP 填充。 */
@Data
@TableName("ems_execution_record")
public class EmsExecutionRecord {

	/** 租户 ID */
	private Long tenantId;

	/** 执行记录 ID（主键，自增） */
	@TableId(type = IdType.AUTO)
	private Long execId;

	/** 关联计划 ID */
	private Long planId;

	/** 下发指令 ID（用于与设备回执关联） */
	private String commandId;

	/** 计划点时刻（5min 粒度），调度器按点到点下发的锚点，配合 (plan_id, plan_time) 唯一键防重复 */
	private LocalTime planTime;

	/** 关联设备 ID */
	private Long deviceId;

	/** 动作方向；CHARGE=充电，DISCHARGE=放电，STANDBY=待机 */
	private String action;

	/** 点执行状态，取值见 {@link PlanPointState}（PENDING/DISPATCHED/SUCCESS/FAILED/TIMEOUT） */
	private PlanPointState state;

	/** 下发参数 JSON */
	private String params;

	/** 执行回执 JSON */
	private String result;

	/** 执行时间（DB DEFAULT CURRENT_TIMESTAMP） */
	private LocalDateTime executeTime;

}
