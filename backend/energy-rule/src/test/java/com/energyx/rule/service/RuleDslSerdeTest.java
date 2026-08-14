package com.energyx.rule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL JSON 序列化/反序列化往返测试（验证 RuleService.buildConfig 解析链路与存库 JSON 可还原）。
 */
class RuleDslSerdeTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	/** 模拟引擎侧：从行投影 JSON 还原 RuleConfig */
	private RuleConfig roundTrip(SceneRuleRow row) {
		// 直接复用 RuleService 的解析语义（构造最小服务）
		RuleService service = new RuleService(null, null, objectMapper, null);
		return service.buildConfig(row);
	}

	@Test
	@DisplayName("往返：完整 TCA 配置 JSON 可还原")
	void roundTripFullConfig() throws Exception {
		SceneRuleRow row = new SceneRuleRow();
		row.setRuleId(1L);
		row.setRuleName("电芯高温降功率");
		row.setDslVersion(1);

		RuleConfig config = new RuleConfig();
		config.setDslVersion(1);
		config.setName("电芯高温降功率");

		RuleTrigger trigger = new RuleTrigger();
		trigger.setType("PROPERTY");
		trigger.setProperty("cellTemp");
		trigger.setOp("GT");
		trigger.setValue(50);
		config.setTriggers(List.of(trigger));

		RuleAction action = new RuleAction();
		action.setType("DEVICE_COMMAND");
		action.setCommand("setPower");
		action.setParams(Map.of("power", 30));
		config.setActions(List.of(action));

		row.setTriggerJson(objectMapper.writeValueAsString(config.getTriggers()));
		row.setConditionJson("[]");
		row.setActionJson(objectMapper.writeValueAsString(config.getActions()));

		RuleConfig restored = roundTrip(row);
		assertNotNull(restored);
		assertEquals(1, restored.getTriggers().size());
		assertEquals("PROPERTY", restored.getTriggers().get(0).getType());
		assertEquals("GT", restored.getTriggers().get(0).getOp());
		assertEquals(50, restored.getTriggers().get(0).getValue());
		assertEquals(1, restored.getActions().size());
		assertEquals("DEVICE_COMMAND", restored.getActions().get(0).getType());
		assertEquals(30, restored.getActions().get(0).getParams().get("power"));
		assertTrue(restored.getConditions().isEmpty());
	}

	@Test
	@DisplayName("空 condition JSON（[]）解析为空列表而非异常")
	void emptyConditionJson() throws Exception {
		SceneRuleRow row = new SceneRuleRow();
		row.setTriggerJson("[]");
		row.setConditionJson("[]");
		row.setActionJson("[]");
		RuleConfig restored = roundTrip(row);
		assertNotNull(restored);
		assertTrue(restored.getTriggers().isEmpty());
		assertTrue(restored.getActions().isEmpty());
	}

	@Test
	@DisplayName("异常 JSON 解析降级为空列表（不抛异常）")
	void malformedJsonDegrades() {
		SceneRuleRow row = new SceneRuleRow();
		row.setTriggerJson("{not-json");
		row.setConditionJson("[]");
		row.setActionJson("[]");
		RuleConfig restored = roundTrip(row);
		assertNotNull(restored);
		assertTrue(restored.getTriggers().isEmpty());
	}

}
