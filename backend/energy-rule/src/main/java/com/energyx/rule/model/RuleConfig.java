package com.energyx.rule.model;

import lombok.Data;

import java.util.List;

/**
 * 场景联动规则 DSL 根配置（iot_scene_rule 的 trigger_json/condition_json/action_json/recovery_json
 * 合并视图）。
 *
 * <p>
 * TCA 模型语义（对齐 Phase11 设计 §4）：
 * <ul>
 * <li>triggers 多触发器 OR：任一命中即进入条件判断；</li>
 * <li>conditions 多条件 AND：全部满足才执行动作，为空数组视为恒真；</li>
 * <li>actions 多动作独立执行：单个失败不影响其他；</li>
 * <li>recovery 可选：条件从满足→不满足时执行恢复动作（不受防抖限制）。</li>
 * </ul>
 * </p>
 */
@Data
public class RuleConfig {

	/**
	 * DSL 版本（当前 1，升级不破坏旧规则）。
	 */
	private Integer dslVersion = 1;

	/**
	 * 规则名称（冗余自 iot_scene_rule.rule_name）。
	 */
	private String name;

	/**
	 * 触发器列表（OR，至少 1 个），元素字段说明见 {@link RuleTrigger}。
	 */
	private List<RuleTrigger> triggers;

	/**
	 * 执行条件列表（AND，可为空数组，空数组视为恒真），元素字段说明见 {@link RuleCondition}。
	 */
	private List<RuleCondition> conditions;

	/**
	 * 执行动作列表（至少 1 个），元素字段说明见 {@link RuleAction}。
	 */
	private List<RuleAction> actions;

	/**
	 * 恢复配置（可选），字段说明见 {@link RuleRecovery}。
	 */
	private RuleRecovery recovery;

}
