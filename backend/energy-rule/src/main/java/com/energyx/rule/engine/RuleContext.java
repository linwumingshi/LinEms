package com.energyx.rule.engine;

import lombok.Data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 规则引擎触发上下文（一次事件触发携带的全部信息，供 Trigger 匹配 / Condition 求值 / Action 执行）。
 *
 * <p>
 * 由各类消费者（属性/上下线/告警/定时/手动）构造，规则引擎内部流转不变。
 * </p>
 */
@Data
public class RuleContext {

	/** 触发类型：PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL/RULE */
	private String triggerType;

	/** 触发设备 ID（设备类触发必填，定时/手动可空） */
	private Long deviceId;

	/** 租户 ID */
	private Long tenantId;

	/** 产品标识 */
	private String productKey;

	/** 设备名 */
	private String deviceName;

	/** 属性上报载荷（PROPERTY 触发时注入，键=物模型 identifier） */
	private Map<String, Object> properties = new LinkedHashMap<>();

	/** 生命周期事件 ONLINE/OFFLINE（LIFECYCLE 触发时注入） */
	private String lifecycleEvent;

	/** 告警载荷（ALARM 触发时注入） */
	private Map<String, Object> alarm = new LinkedHashMap<>();

	/** 手动触发载荷（MANUAL 触发时注入，任意 JSON） */
	private Map<String, Object> payload = new LinkedHashMap<>();

	/** 嵌套规则环检测集合（RULE 动作沿调用链传递，Phase 11 §7.4） */
	private Set<Long> visitedRuleIds = new HashSet<>();

	/** 事件时间（毫秒） */
	private Long ts;

	/** 原始消息（诊断用，可空） */
	private String raw;

}
