package com.energyx.rule.job;

import com.energyx.rule.engine.RuleCache;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时 job 管理测试：TIMER 触发器 → upsert / 移除 → remove / 非 TIMER 规则 → remove。
 */
class TimerJobManagerTest {

	private RuleCache ruleCache;

	private XxlJobAdminClient adminClient;

	private TimerJobManager manager;

	@BeforeEach
	void setUp() {
		ruleCache = mock(RuleCache.class);
		adminClient = mock(XxlJobAdminClient.class);
		when(adminClient.upsert(Mockito.anyLong(), Mockito.anyString())).thenReturn(true);
		when(adminClient.remove(Mockito.anyLong())).thenReturn(true);
		manager = new TimerJobManager(ruleCache, adminClient);
	}

	private RuleCache.CachedRule ruleWithTimer(long id, String cron) {
		SceneRuleRow row = new SceneRuleRow();
		row.setRuleId(id);
		row.setRuleCode("R" + id);
		RuleConfig config = new RuleConfig();
		RuleTrigger timer = new RuleTrigger();
		timer.setType("TIMER");
		timer.setCron(cron);
		config.setTriggers(List.of(timer));
		return new RuleCache.CachedRule(row, config);
	}

	private RuleCache.CachedRule ruleWithoutTimer(long id) {
		SceneRuleRow row = new SceneRuleRow();
		row.setRuleId(id);
		row.setRuleCode("R" + id);
		RuleConfig config = new RuleConfig();
		RuleTrigger prop = new RuleTrigger();
		prop.setType("PROPERTY");
		config.setTriggers(List.of(prop));
		return new RuleCache.CachedRule(row, config);
	}

	@Test
	@DisplayName("含 TIMER 触发器规则 → upsert（cron 取自触发器）")
	void timerRuleUpsert() {
		when(ruleCache.get(1L)).thenReturn(ruleWithTimer(1L, "0 30 22 * * ?"));
		manager.onRuleChanged("1");
		verify(adminClient).upsert(eq(1L), eq("0 30 22 * * ?"));
	}

	@Test
	@DisplayName("无 TIMER 触发器规则 → remove（无定时 job）")
	void nonTimerRuleRemove() {
		when(ruleCache.get(2L)).thenReturn(ruleWithoutTimer(2L));
		manager.onRuleChanged("2");
		verify(adminClient).remove(2L);
		verify(adminClient, never()).upsert(Mockito.anyLong(), Mockito.anyString());
	}

	@Test
	@DisplayName("规则已停用/删除（缓存无）→ remove")
	void missingRuleRemove() {
		when(ruleCache.get(3L)).thenReturn(null);
		manager.onRuleChanged("3");
		verify(adminClient).remove(3L);
	}

	@Test
	@DisplayName("ALL 全量同步：按当前缓存逐条 upsert/remove")
	void syncAll() {
		when(ruleCache.all()).thenReturn(List.of(ruleWithTimer(1L, "0 0 8 * * ?"), ruleWithoutTimer(2L)));
		manager.onRuleChanged("ALL");
		verify(adminClient).upsert(1L, "0 0 8 * * ?");
		verify(adminClient).remove(2L);
	}

}
