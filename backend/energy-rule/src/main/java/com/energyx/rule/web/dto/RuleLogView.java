package com.energyx.rule.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则执行日志视图（分页查询返回）。
 */
@Data
public class RuleLogView {

	private Long logId;

	private Long ruleId;

	private String ruleCode;

	private Long tenantId;

	/** PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL/RULE */
	private String triggerType;

	private Long deviceId;

	/** 1=条件满足执行 0=触发未过条件 */
	private Integer matched;

	/** 每个动作的执行结果 JSON */
	private String actionResult;

	private Integer costMs;

	private String traceId;

	private LocalDateTime createTime;

}
