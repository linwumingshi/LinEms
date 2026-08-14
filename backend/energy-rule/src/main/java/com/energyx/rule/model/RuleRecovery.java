package com.energyx.rule.model;

import lombok.Data;

import java.util.List;

/**
 * 规则恢复配置（可选）：条件从「满足」回到「不满足」时执行恢复动作（边沿触发）。
 *
 * <p>
 * 仅属性触发规则有恢复语义；recovery 动作不受防抖窗口限制（恢复必须可靠执行）。
 * </p>
 */
@Data
public class RuleRecovery {

	/** 属性标识（必填，与 Trigger.property 对应） */
	private String property;

	/** 恢复条件比较符 GT/GTE/LT/LTE/EQ/NEQ（必填），如温度回落 LTE 45 */
	private String op;

	/** 恢复阈值（必填） */
	private Object value;

	/** 恢复动作列表（必填，复用 RuleAction 定义） */
	private List<RuleAction> actions;

}
