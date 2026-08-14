package com.energyx.rule.engine;

import com.energyx.common.util.SnowflakeIdGenerator;
import com.energyx.common.web.TraceContext;
import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.entity.SceneExecLogRow;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.mapper.SceneExecLogMapper;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleRecovery;
import com.energyx.rule.model.RuleTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎统一入口（Phase 11 设计 §7）：触发匹配 → 条件求值 → 防抖/恢复边沿 → 执行日志。
 *
 * <p>
 * 处理链路（属性/生命周期/告警三类事件共用）：
 * <ol>
 * <li>按触发维度取候选规则（RuleCache 索引粗筛）；</li>
 * <li>逐条 Trigger OR 匹配（TriggerMatcher）；</li>
 * <li>Condition AND 求值（ConditionEvaluator）；</li>
 * <li>属性触发规则走防抖 + 恢复边沿状态机（DebounceGuard / RecoveryTracker）；</li>
 * <li>条件满足 → 执行动作（Phase C 接入 ActionExecutor），本次先落执行日志 matched=1。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class RuleEngine {

	private final RuleCache ruleCache;

	private final TriggerMatcher triggerMatcher;

	private final ConditionEvaluator conditionEvaluator;

	private final DebounceGuard debounceGuard;

	private final RecoveryTracker recoveryTracker;

	private final SceneExecLogMapper logMapper;

	private final SnowflakeIdGenerator idGenerator;

	private final ObjectMapper objectMapper;

	private final RuleProperties props;

	public RuleEngine(RuleCache ruleCache, TriggerMatcher triggerMatcher, ConditionEvaluator conditionEvaluator,
			DebounceGuard debounceGuard, RecoveryTracker recoveryTracker, SceneExecLogMapper logMapper,
			SnowflakeIdGenerator idGenerator, ObjectMapper objectMapper, RuleProperties props) {
		this.ruleCache = ruleCache;
		this.triggerMatcher = triggerMatcher;
		this.conditionEvaluator = conditionEvaluator;
		this.debounceGuard = debounceGuard;
		this.recoveryTracker = recoveryTracker;
		this.logMapper = logMapper;
		this.idGenerator = idGenerator;
		this.objectMapper = objectMapper;
		this.props = props;
	}

	/** 属性上报触发入口（iot-thing-property） */
	public void onProperty(RuleContext ctx) {
		long start = System.currentTimeMillis();
		List<RuleCache.CachedRule> candidates = ruleCache.candidatesForProperty(ctx.getProductKey(), null);
		for (RuleCache.CachedRule cached : candidates) {
			processRule(cached, ctx, start);
		}
	}

	/** 生命周期触发入口（iot-device-lifecycle） */
	public void onLifecycle(RuleContext ctx) {
		long start = System.currentTimeMillis();
		List<RuleCache.CachedRule> candidates = ruleCache.candidatesForLifecycle();
		for (RuleCache.CachedRule cached : candidates) {
			processRule(cached, ctx, start);
		}
	}

	/** 告警触发入口（iot-alarm） */
	public void onAlarm(RuleContext ctx) {
		long start = System.currentTimeMillis();
		List<RuleCache.CachedRule> candidates = ruleCache.candidatesForAlarm();
		for (RuleCache.CachedRule cached : candidates) {
			processRule(cached, ctx, start);
		}
	}

	/**
	 * 定时/手动触发入口（Phase D：xxl-job 调度中心 job 与 POST /rule/{id}/trigger 调用）。
	 *
	 * <p>
	 * 定时触发按 ruleId 精确取规则（job 参数携带 ruleId），手动触发取全部含 MANUAL 触发器的规则； 两类触发无设备上下文，条件求值仅支持
	 * TIME_RANGE / DEVICE_STATUS 类（PROPERTY 条件无上下文读影子）。
	 * </p>
	 */
	public void onScheduled(Long ruleId, RuleContext ctx) {
		long start = System.currentTimeMillis();
		RuleCache.CachedRule cached = ruleCache.get(ruleId);
		if (cached == null) {
			log.warn("[Rule] 定时触发规则不存在/未启用 ruleId={}", ruleId);
			return;
		}
		processRule(cached, ctx, start);
	}

	/** 手动触发入口（POST /rule/{id}/trigger） */
	public void onManual(Long ruleId, RuleContext ctx) {
		long start = System.currentTimeMillis();
		RuleCache.CachedRule cached = ruleCache.get(ruleId);
		if (cached == null) {
			log.warn("[Rule] 手动触发规则不存在/未启用 ruleId={}", ruleId);
			return;
		}
		processRule(cached, ctx, start);
	}

	/**
	 * 单条规则处理：Trigger OR → Condition AND → 防抖/恢复 → 动作（Phase C）+ 执行日志。
	 */
	private void processRule(RuleCache.CachedRule cached, RuleContext ctx, long start) {
		SceneRuleRow row = cached.row();
		RuleConfig config = cached.config();
		try {
			// 1. Trigger OR 匹配：任一命中即进入条件判断；
			// TIMER/MANUAL 触发由外部驱动（xxl-job cron / 手动 API），此处只需确认规则含对应类型触发器即可
			boolean triggered = isExternalDriven(ctx.getTriggerType())
					? containsTriggerType(config, ctx.getTriggerType()) : anyTriggerMatches(config, ctx);
			if (!triggered) {
				return;
			}
			// 2. Condition AND 求值
			boolean conditionsMet = conditionEvaluator.evaluate(config.getConditions(), ctx);
			long deviceId = ctx.getDeviceId() == null ? 0L : ctx.getDeviceId();

			// 3. 恢复边沿：条件从满足→不满足，执行恢复动作（属性触发规则专用）
			if (config.getRecovery() != null && isPropertyTriggered(config, ctx)) {
				handleRecovery(cached, ctx, conditionsMet, deviceId);
			}

			// 4. 防抖：首次满足才执行动作（窗口内不重复）
			if (!conditionsMet) {
				writeLog(row, ctx, deviceId, 0, null, cost(start));
				return;
			}
			boolean fire = true;
			if (isPropertyTriggered(config, ctx)) {
				fire = recoveryTracker.shouldFire(row.getRuleId(), deviceId, true);
			}
			if (!fire) {
				// 持续满足：防抖窗口内不重复执行（仍记日志便于审计）
				writeLog(row, ctx, deviceId, 1, null, cost(start));
				return;
			}
			boolean debounced = debounceGuard.tryPass(row.getRuleId(), deviceId, cached.debounceSeconds());
			if (!debounced) {
				writeLog(row, ctx, deviceId, 1, null, cost(start));
				return;
			}
			// 5. 执行动作（Phase C 接入 ActionExecutor）
			writeLog(row, ctx, deviceId, 1, null, cost(start));
			log.info("[Rule] 规则命中 ruleId={} code={} trigger={} deviceId={} conditionsMet=true", row.getRuleId(),
					row.getRuleCode(), ctx.getTriggerType(), deviceId);
		}
		catch (Exception e) {
			log.error("[Rule] 规则处理异常 ruleId={} trigger={}", row.getRuleId(), ctx.getTriggerType(), e);
			writeLog(row, ctx, ctx.getDeviceId() == null ? 0L : ctx.getDeviceId(), 0, Map.of("error", e.getMessage()),
					cost(start));
		}
	}

	/** 恢复动作处理：边沿下降沿（FIRED→RECOVERED）执行恢复动作 */
	private void handleRecovery(RuleCache.CachedRule cached, RuleContext ctx, boolean conditionsMet, long deviceId) {
		RuleRecovery recovery = cached.config().getRecovery();
		if (recovery == null || recovery.getActions() == null || recovery.getActions().isEmpty()) {
			return;
		}
		// 恢复条件独立比较：当前属性值满足恢复阈值（如 LTE 45）
		Object current = ctx.getProperties() == null ? null : ctx.getProperties().get(recovery.getProperty());
		boolean recovered = current != null
				&& recoveryTracker.recoveryMet(recovery.getOp(), current, recovery.getValue());
		boolean shouldRecover = recoveryTracker.shouldRecover(cached.id(), deviceId, conditionsMet);
		if (recovered && shouldRecover) {
			log.info("[Rule] 恢复边沿触发 ruleId={} code={} deviceId={}（Phase C 执行恢复动作）", cached.id(),
					cached.row().getRuleCode(), deviceId);
		}
	}

	private boolean isPropertyTriggered(RuleConfig config, RuleContext ctx) {
		return containsTriggerType(config, "PROPERTY");
	}

	/** 规则是否含指定类型触发器 */
	private boolean containsTriggerType(RuleConfig config, String type) {
		if (config.getTriggers() == null) {
			return false;
		}
		for (RuleTrigger t : config.getTriggers()) {
			if (type.equals(t.getType())) {
				return true;
			}
		}
		return false;
	}

	/** 任一触发器 OR 匹配（非 TIMER/MANUAL 类） */
	private boolean anyTriggerMatches(RuleConfig config, RuleContext ctx) {
		if (config.getTriggers() == null) {
			return false;
		}
		for (RuleTrigger trigger : config.getTriggers()) {
			if (triggerMatcher.matches(trigger, ctx)) {
				return true;
			}
		}
		return false;
	}

	/** TIMER/MANUAL 触发由外部驱动，无需再次匹配 */
	private boolean isExternalDriven(String triggerType) {
		return "TIMER".equals(triggerType) || "MANUAL".equals(triggerType);
	}

	/** 执行日志落库（权威源 MySQL，动作结果 Phase C 填充） */
	private void writeLog(SceneRuleRow row, RuleContext ctx, long deviceId, int matched,
			Map<String, Object> actionResult, int costMs) {
		try {
			SceneExecLogRow logRow = new SceneExecLogRow();
			logRow.setLogId(idGenerator.nextId());
			logRow.setRuleId(row.getRuleId());
			logRow.setRuleCode(row.getRuleCode());
			logRow.setTenantId(row.getTenantId());
			logRow.setTriggerType(ctx.getTriggerType());
			logRow.setDeviceId(deviceId == 0 ? null : deviceId);
			logRow.setMatched(matched);
			logRow.setActionResult(actionResult == null ? null : objectMapper.writeValueAsString(actionResult));
			logRow.setCostMs(costMs);
			logRow.setTraceId(TraceContext.getTraceId());
			logRow.setCreateTime(LocalDateTime.now());
			logMapper.insert(logRow);
		}
		catch (Exception e) {
			// 执行日志失败不影响引擎主链路（降级：仅记日志）
			log.warn("[Rule] 执行日志落库失败 ruleId={}", row.getRuleId(), e);
		}
	}

	private int cost(long start) {
		return (int) (System.currentTimeMillis() - start);
	}

}
