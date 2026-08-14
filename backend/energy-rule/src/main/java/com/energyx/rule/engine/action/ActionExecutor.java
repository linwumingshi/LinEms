package com.energyx.rule.engine.action;

import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;

/**
 * 动作执行器 SPI（Phase 11 设计 §4.5 / §7.4）。
 *
 * <p>
 * 每个动作类型一个实现，独立 try-catch：单个动作失败不影响其他动作（由 ActionExecutorService 编排）， 失败信息写入执行日志
 * action_result。
 * </p>
 */
public interface ActionExecutor {

	/** 动作类型标识（DEVICE_COMMAND/ALARM/NOTIFY/RULE），用于分发 */
	String type();

	/** 执行动作，返回结果（成功/失败/信息），实现不得抛出未捕获异常 */
	ActionResult execute(RuleAction action, RuleContext ctx);

}
