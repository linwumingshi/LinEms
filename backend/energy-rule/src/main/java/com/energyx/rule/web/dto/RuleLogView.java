package com.energyx.rule.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则执行日志视图（分页查询返回）。
 */
@Data
public class RuleLogView {

	/**
	 * 日志 ID（主键）。
	 */
	private Long logId;

	/**
	 * 关联规则 ID。
	 */
	private Long ruleId;

	/**
	 * 关联规则编码。
	 */
	private String ruleCode;

	/**
	 * 租户 ID。
	 */
	private Long tenantId;

	/**
	 * 触发器类型：PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL/RULE。
	 */
	private String triggerType;

	/**
	 * 关联设备 ID（无设备触发时为空）。
	 */
	private Long deviceId;

	/**
	 * 是否命中条件执行，1=条件满足并执行动作 0=触发但未通过条件。
	 */
	private Integer matched;

	/**
	 * 每个动作的执行结果 JSON。
	 */
	private String actionResult;

	/**
	 * 本次规则执行耗时（毫秒）。
	 */
	private Integer costMs;

	/**
	 * 链路追踪 ID。
	 */
	private String traceId;

	/**
	 * 创建时间（即执行时间）。
	 */
	private LocalDateTime createTime;

}
