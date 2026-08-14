package com.energyx.rule.job;

import com.energyx.common.redis.DistributedLock;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import com.energyx.rule.util.RuleRedisKeys;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 规则定时触发执行器（Phase 11 设计 §10）：xxl-job 调度中心 job 的 executorHandler=sceneRuleTimer。
 *
 * <p>
 * 触发链路：调度中心 cron 到点 → 本处理器（executorParam=ruleId）→ 抢分布式锁
 * {@code lock:scheduled:rule-{ruleId}}（路由 FIRST + 锁双保险防重）→ 构造 RuleContext（TIMER 类型） →
 * RuleEngine.onScheduled 走统一引擎（Trigger 校验含 TIMER → Conditions → Actions）。
 * </p>
 */
@Slf4j
@Component
public class SceneRuleTimerJob {

	private final RuleEngine ruleEngine;

	private final DistributedLock distributedLock;

	public SceneRuleTimerJob(RuleEngine ruleEngine, DistributedLock distributedLock) {
		this.ruleEngine = ruleEngine;
		this.distributedLock = distributedLock;
	}

	/** xxl-job 定时触发入口（执行参数=ruleId） */
	@XxlJob("sceneRuleTimer")
	public void execute() {
		String param = XxlJobHelper.getJobParam();
		Long ruleId;
		try {
			ruleId = Long.parseLong(param == null ? "" : param.trim());
		}
		catch (NumberFormatException e) {
			log.warn("[Rule] 定时触发参数非法 param={}", param);
			XxlJobHelper.log("定时触发参数非法 param={}", param);
			return;
		}
		// 多实例防重：路由 FIRST + 分布式锁双保险（锁 TTL 60s，定时任务单次执行远小于该值）
		boolean locked = distributedLock.tryLock(RuleRedisKeys.scheduledLock(ruleId), 60);
		if (!locked) {
			log.debug("[Rule] 定时触发锁被占用，跳过 ruleId={}", ruleId);
			return;
		}
		try {
			RuleContext ctx = new RuleContext();
			ctx.setTriggerType("TIMER");
			ctx.setTs(System.currentTimeMillis());
			ruleEngine.onScheduled(ruleId, ctx);
		}
		finally {
			distributedLock.unlock(RuleRedisKeys.scheduledLock(ruleId));
		}
	}

}
