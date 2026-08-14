package com.energyx.rule.engine;

import com.energyx.rule.entity.SceneRuleRow;
import com.energyx.rule.mapper.SceneRuleMapper;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleCondition;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleDevice;
import com.energyx.rule.model.RuleRecovery;
import com.energyx.rule.model.RuleTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 场景联动规则 DSL 校验器测试（合法配置 / 各类型必填校验 / 嵌套规则防环）。
 */
class DslValidatorTest {

	private DslValidator validator;

	private SceneRuleMapper ruleMapper;

	@BeforeEach
	void setUp() {
		ruleMapper = Mockito.mock(SceneRuleMapper.class);
		// 目标规则存在（ruleId=2）
		SceneRuleRow target = new SceneRuleRow();
		target.setRuleId(2L);
		Mockito.when(ruleMapper.selectById(2L)).thenReturn(target);
		validator = new DslValidator(ruleMapper);
	}

	private RuleConfig validConfig() {
		RuleConfig config = new RuleConfig();
		RuleTrigger trigger = new RuleTrigger();
		trigger.setType("PROPERTY");
		RuleDevice device = new RuleDevice();
		device.setProductKey("energyx_pcs");
		device.setDeviceName("PCS-001");
		trigger.setDevice(device);
		trigger.setProperty("cellTemp");
		trigger.setOp("GT");
		trigger.setValue(50);
		config.setTriggers(List.of(trigger));

		RuleAction action = new RuleAction();
		action.setType("DEVICE_COMMAND");
		action.setDevice(device);
		action.setCommand("setPower");
		action.setParams(Map.of("power", 30));
		config.setActions(List.of(action));
		return config;
	}

	@Test
	@DisplayName("合法：完整 PROPERTY 触发 + DEVICE_COMMAND 动作")
	void validPropertyConfig() {
		assertDoesNotThrow(() -> validator.validate(validConfig(), null));
	}

	@Test
	@DisplayName("非法：triggers 为空")
	void emptyTriggers() {
		RuleConfig config = validConfig();
		config.setTriggers(List.of());
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：未知触发器类型")
	void unknownTriggerType() {
		RuleConfig config = validConfig();
		RuleTrigger bad = new RuleTrigger();
		bad.setType("UNKNOWN");
		config.setTriggers(List.of(bad));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：PROPERTY 触发器缺 op")
	void propertyMissingOp() {
		RuleConfig config = validConfig();
		config.getTriggers().get(0).setOp(null);
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：非法比较操作符")
	void invalidOp() {
		RuleConfig config = validConfig();
		config.getTriggers().get(0).setOp("LIKES");
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：TIMER 触发器 cron 非法")
	void invalidTimerCron() {
		RuleConfig config = validConfig();
		RuleTrigger timer = new RuleTrigger();
		timer.setType("TIMER");
		timer.setCron("0 30 22 * *"); // 5 位非法
		config.setTriggers(List.of(timer));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("合法：TIMER 触发器 cron 合法（6 位）")
	void validTimerCron() {
		RuleConfig config = validConfig();
		RuleTrigger timer = new RuleTrigger();
		timer.setType("TIMER");
		timer.setCron("0 30 22 * * ?");
		config.setTriggers(List.of(timer));
		assertDoesNotThrow(() -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：actions 为空")
	void emptyActions() {
		RuleConfig config = validConfig();
		config.setActions(List.of());
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：DEVICE_COMMAND 动作缺 command")
	void actionMissingCommand() {
		RuleConfig config = validConfig();
		config.getActions().get(0).setCommand(null);
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：RULE 嵌套动作指向自身（防环）")
	void nestedRuleSelfReference() {
		RuleConfig config = validConfig();
		RuleAction nested = new RuleAction();
		nested.setType("RULE");
		nested.setRuleId(10L); // selfRuleId=10
		config.setActions(List.of(nested));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, 10L));
	}

	@Test
	@DisplayName("非法：RULE 嵌套动作目标不存在")
	void nestedRuleTargetMissing() {
		RuleConfig config = validConfig();
		RuleAction nested = new RuleAction();
		nested.setType("RULE");
		nested.setRuleId(999L);
		config.setActions(List.of(nested));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, 10L));
	}

	@Test
	@DisplayName("合法：RULE 嵌套动作目标存在且非自身")
	void nestedRuleValid() {
		RuleConfig config = validConfig();
		RuleAction nested = new RuleAction();
		nested.setType("RULE");
		nested.setRuleId(2L);
		config.setActions(List.of(nested));
		assertDoesNotThrow(() -> validator.validate(config, 10L));
	}

	@Test
	@DisplayName("合法：多条件 AND（DEVICE_STATUS + TIME_RANGE）")
	void validConditions() {
		RuleConfig config = validConfig();
		RuleCondition online = new RuleCondition();
		online.setType("DEVICE_STATUS");
		online.setDevice(config.getTriggers().get(0).getDevice());
		online.setStatus("ONLINE");
		RuleCondition timeRange = new RuleCondition();
		timeRange.setType("TIME_RANGE");
		timeRange.setStart("12:00");
		timeRange.setEnd("23:59");
		config.setConditions(List.of(online, timeRange));
		assertDoesNotThrow(() -> validator.validate(config, null));
	}

	@Test
	@DisplayName("非法：TIME_RANGE 格式错误")
	void invalidTimeRange() {
		RuleConfig config = validConfig();
		RuleCondition timeRange = new RuleCondition();
		timeRange.setType("TIME_RANGE");
		timeRange.setStart("12点");
		timeRange.setEnd("23:59");
		config.setConditions(List.of(timeRange));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(config, null));
	}

	@Test
	@DisplayName("合法：recovery 恢复配置（动作复用校验）")
	void validRecovery() {
		RuleConfig config = validConfig();
		RuleRecovery recovery = new RuleRecovery();
		recovery.setProperty("cellTemp");
		recovery.setOp("LTE");
		recovery.setValue(45);
		RuleAction recoverAction = new RuleAction();
		recoverAction.setType("DEVICE_COMMAND");
		recoverAction.setDevice(config.getTriggers().get(0).getDevice());
		recoverAction.setCommand("setPower");
		recoverAction.setParams(Map.of("power", 100));
		recovery.setActions(List.of(recoverAction));
		config.setRecovery(recovery);
		assertDoesNotThrow(() -> validator.validate(config, null));
	}

}
