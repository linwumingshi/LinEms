package com.energyx.rule.job;

import com.energyx.rule.engine.RuleCache;
import com.energyx.rule.model.RuleTrigger;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则 TIMER 触发器 → xxl-job 动态 job 管理（Phase 11 设计 §10）。
 *
 * <p>
 * 监听 {@link RuleCache} 规则变更（rule:changed 增量 / 启动与定时兜底全量）， 对含 TIMER 触发器的启用规则在调度中心 upsert
 * job（jobKey=rule-{ruleId}，cron=首个 TIMER 触发器）； 规则停用/删除/不含 TIMER 时移除
 * job。调度中心不可达时降级记日志（不影响规则引擎主链路）。
 * </p>
 */
@Slf4j
@Component
public class TimerJobManager {

	private final RuleCache ruleCache;

	private final XxlJobAdminClient adminClient;

	public TimerJobManager(RuleCache ruleCache, XxlJobAdminClient adminClient) {
		this.ruleCache = ruleCache;
		this.adminClient = adminClient;
	}

	@PostConstruct
	public void init() {
		ruleCache.setChangeListener(this::onRuleChanged);
	}

	/** 规则变更回调：ALL 全量同步；{ruleId} 单条同步（启停/增删/改 cron）。包级可见（RuleCache 监听器引用） */
	void onRuleChanged(String ruleIdOrAll) {
		if ("ALL".equals(ruleIdOrAll)) {
			syncAll();
			return;
		}
		try {
			Long ruleId = Long.parseLong(ruleIdOrAll);
			RuleCache.CachedRule cached = ruleCache.get(ruleId);
			String cron = cached == null ? null : firstTimerCron(cached.config().getTriggers());
			if (cron != null) {
				adminClient.upsert(ruleId, cron);
			}
			else {
				adminClient.remove(ruleId);
			}
		}
		catch (NumberFormatException e) {
			log.warn("[XxlJob] 规则变更回调参数非法 ruleIdOrAll={}", ruleIdOrAll);
		}
	}

	/** 全量同步：所有启用规则按 TIMER 触发器 upsert/remove（启动与 ALL 广播用） */
	private void syncAll() {
		int upserted = 0;
		int removed = 0;
		for (RuleCache.CachedRule cached : ruleCache.all()) {
			String cron = firstTimerCron(cached.config().getTriggers());
			if (cron != null) {
				if (adminClient.upsert(cached.id(), cron)) {
					upserted++;
				}
			}
			else if (adminClient.remove(cached.id())) {
				removed++;
			}
		}
		log.info("[XxlJob] 规则定时 job 全量同步 upsert={} remove={}", upserted, removed);
	}

	/** 取规则首个 TIMER 触发器的 cron（多 TIMER 触发器取第一个，设计约束单 cron） */
	private String firstTimerCron(List<RuleTrigger> triggers) {
		if (triggers == null) {
			return null;
		}
		for (RuleTrigger t : triggers) {
			if ("TIMER".equals(t.getType()) && t.getCron() != null && !t.getCron().isBlank()) {
				return t.getCron();
			}
		}
		return null;
	}

}
