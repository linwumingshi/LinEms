package com.energyx.rule.engine.action;

import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动作执行编排器（Phase 11 设计 §7.4）：按类型分发到具体执行器，多动作独立执行互不影响。
 *
 * <p>
 * 每个动作独立 try-catch：失败记录 ActionResult.fail 到执行日志，不中断后续动作。
 * 同一规则的多个动作顺序执行（模板渲染/命令下发顺序敏感场景可控）。
 * </p>
 */
@Component
public class ActionExecutorService {

	private final Map<String, ActionExecutor> executors;

	public ActionExecutorService(List<ActionExecutor> executorList) {
		this.executors = executorList.stream().collect(Collectors.toMap(ActionExecutor::type, Function.identity()));
	}

	/**
	 * 执行单个动作。
	 * @return 执行结果（异常被捕获转 fail，不向上抛）
	 */
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		if (action == null || action.getType() == null) {
			return ActionResult.fail("UNKNOWN", "动作或类型为空");
		}
		ActionExecutor executor = executors.get(action.getType());
		if (executor == null) {
			return ActionResult.fail(action.getType(), "未注册的动作类型: " + action.getType());
		}
		try {
			return executor.execute(action, ctx);
		}
		catch (Exception e) {
			return ActionResult.fail(action.getType(), "执行异常: " + e.getMessage());
		}
	}

	/** 顺序执行动作列表，返回逐动作结果 */
	public java.util.List<ActionResult> executeAll(List<RuleAction> actions, RuleContext ctx) {
		return actions.stream().map(a -> execute(a, ctx)).toList();
	}

}
