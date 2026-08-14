package com.energyx.rule.engine.action;

import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import com.energyx.rule.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * 嵌套规则动作执行器（RULE）。
 *
 * <p>
 * 阿里云 Rule Output 语义：跳转目标规则（跳过其 Trigger 匹配，直接评估 Condition，满足则执行其 Actions）。 深度 ≤
 * 5（RuleProperties.nestMaxDepth）+ 环检测（RuleContext.visitedRuleIds 沿调用链传递），
 * 成环/超深拒绝执行并返回失败结果。
 * </p>
 */
@Component
public class NestedRuleAction implements ActionExecutor {

	private final RuleEngine ruleEngine;

	public NestedRuleAction(RuleEngine ruleEngine) {
		this.ruleEngine = ruleEngine;
	}

	@Override
	public String type() {
		return "RULE";
	}

	@Override
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		if (action.getRuleId() == null) {
			return ActionResult.fail(type(), "RULE 动作缺 ruleId");
		}
		var results = ruleEngine.executeNested(action.getRuleId(), ctx, 1);
		if (results.isEmpty()) {
			return ActionResult.fail(type(), "嵌套规则未执行（条件不满足/深度超限/成环）: " + action.getRuleId());
		}
		return ActionResult.ok(type(), "嵌套规则已执行 ruleId=" + action.getRuleId() + " actions=" + results.size());
	}

}
