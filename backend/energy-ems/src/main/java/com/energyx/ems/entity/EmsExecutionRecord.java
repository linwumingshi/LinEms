package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** 策略执行记录（ems_execution_record）。execute_time 由 DB DEFAULT CURRENT_TIMESTAMP 填充。 */
@Data
@TableName("ems_execution_record")
public class EmsExecutionRecord {

	@TableId(type = IdType.AUTO)
	private Long execId;

	private Long tenantId;

	private Long planId;

	private String commandId;

	/** 计划点时刻（5min 粒度），调度器按点到点下发的锚点，配合 (plan_id, plan_time) 唯一键防重复 */
	private LocalTime planTime;

	private Long deviceId;

	/** CHARGE/DISCHARGE/STANDBY */
	private String action;

	/** 点执行状态：0待下发 1已下发 2成功 3失败 4超时 */
	private Integer state;

	/** 下发参数 JSON */
	private String params;

	/** 执行回执 JSON */
	private String result;

	private LocalDateTime executeTime;

}
