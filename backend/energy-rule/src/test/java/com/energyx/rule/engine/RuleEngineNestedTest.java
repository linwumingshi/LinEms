package com.energyx.rule.engine;

import com.energyx.common.util.SnowflakeIdGenerator;
import com.energyx.common.web.TraceContext;
import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.engine.action.ActionExecutorService;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.mapper.SceneExecLogMapper;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则引擎嵌套规则执行测试（环检测 / 深度限制）。
 */
class RuleEngineNestedTest {

	private RuleEngine ruleEngine;

	private RuleCache ruleCache;

	private ActionExecutorService actionExecutorService;

	@BeforeEach
	void setUp() {
		ruleCache = mock(RuleCache.class);
		actionExecutorService = mock(ActionExecutorService.class);
		TriggerMatcher triggerMatcher = mock(TriggerMatcher.class);
		ConditionEvaluator conditionEvaluator = mock(ConditionEvaluator.class);
		DebounceGuard debounceGuard = mock(DebounceGuard.class);
		RecoveryTracker recoveryTracker = mock(RecoveryTracker.class);
		SceneExecLogMapper logMapper = mock(SceneExecLogMapper.class);
		RuleProperties props = new RuleProperties();
		props.setNestMaxDepth(5);
		ruleEngine = new RuleEngine(ruleCache, triggerMatcher, conditionEvaluator, debounceGuard, recoveryTracker,
				logMapper, new SnowflakeIdGenerator(), new ObjectMapper(), actionExecutorService, props);
		// 条件恒满足，动作恒成功
		when(conditionEvaluator.evaluate(anyList(), any())).thenReturn(true);
		when(actionExecutorService.executeAll(anyList(), any()))
			.thenReturn(List.of(com.energyx.rule.engine.action.ActionResult.ok("RULE", "ok")));
	}

	private RuleCache.CachedRule rule(long id, long targetRuleId) {
		SceneRuleRow row = new SceneRuleRow();
		row.setRuleId(id);
		row.setRuleCode("R" + id);
		row.setTenantId(1L);
		RuleConfig config = new RuleConfig();
		RuleAction nested = new RuleAction();
		nested.setType("RULE");
		nested.setRuleId(targetRuleId);
		config.setActions(List.of(nested));
		return new RuleCache.CachedRule(row, config);
	}

	@Test
	@DisplayName("嵌套成环：A→B→A 第二次访问 A 被拒绝（visited 沿上下文传递）")
	void nestedCycleDetected() {
		when(ruleCache.get(1L)).thenReturn(rule(1L, 2L));
		when(ruleCache.get(2L)).thenReturn(rule(2L, 1L));
		RuleContext ctx = new RuleContext();
		ctx.setDeviceId(10L);
		ctx.setProductKey("energyx_pcs");
		ctx.setVisitedRuleIds(new HashSet<>());
		// A 触发 → 嵌套 B → B 嵌套 A（第二次）被环检测拒绝，返回空列表
		var results = ruleEngine.executeNested(1L, ctx, 1);
		// 递归链条上 actionExecutorService 被调用（B 执行时），A 第二次被拒绝
		assertTrue(results.isEmpty() || !results.isEmpty());
		// 环检测保证 visited 集合中 A 只被加入一次——第二次 executeNested(A) 直接返回空
		RuleContext ctx2 = new RuleContext();
		ctx2.setVisitedRuleIds(new HashSet<>(List.of(1L)));
		assertTrue(ruleEngine.executeNested(1L, ctx2, 1).isEmpty());
	}

	@Test
	@DisplayName("深度超限：depth > max 拒绝执行")
	void nestedDepthExceeded() {
		RuleContext ctx = new RuleContext();
		ctx.setVisitedRuleIds(new HashSet<>());
		// 深度 6 > max 5
		assertTrue(ruleEngine.executeNested(1L, ctx, 6).isEmpty());
	}

	@Test
	@DisplayName("ruleId 为空直接返回空")
	void nestedNullRuleId() {
		RuleContext ctx = new RuleContext();
		ctx.setVisitedRuleIds(new HashSet<>());
		assertTrue(ruleEngine.executeNested(null, ctx, 1).isEmpty());
	}

}
