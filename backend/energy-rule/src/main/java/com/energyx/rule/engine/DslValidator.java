package com.energyx.rule.engine;

import com.energyx.rule.mapper.SceneRuleMapper;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleCondition;
import com.energyx.rule.model.RuleConfig;
import com.energyx.rule.model.RuleDevice;
import com.energyx.rule.model.RuleRecovery;
import com.energyx.rule.model.RuleTrigger;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景联动规则 DSL 校验器（创建/更新前置校验，Phase11 设计 §9）。
 *
 * <ul>
 * <li>triggers 至少 1 个、actions 至少 1 个（recovery.actions 同样校验）；</li>
 * <li>各类型必填字段与取值合法性（PROPERTY 的 op/device/property、TIMER 的 cron 等）；</li>
 * <li>TIME_RANGE start/end 合法性；</li>
 * <li>RULE 嵌套动作：目标 ruleId 必须存在且不等于自身（防环，深环在执行期兜底）。</li>
 * </ul>
 */
@Component
public class DslValidator {

	private static final Set<String> TRIGGER_TYPES = Set.of("PROPERTY", "TIMER", "LIFECYCLE", "ALARM", "MANUAL");

	private static final Set<String> CONDITION_TYPES = Set.of("DEVICE_STATUS", "TIME_RANGE", "PROPERTY");

	private static final Set<String> ACTION_TYPES = Set.of("DEVICE_COMMAND", "ALARM", "NOTIFY", "RULE");

	private static final Set<String> OPS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NEQ");

	private static final Set<String> LIFECYCLE_EVENTS = Set.of("ONLINE", "OFFLINE");

	private static final Set<String> ALARM_STATES = Set.of("ACTIVE", "RECOVER");

	private static final Set<String> DEVICE_STATUSES = Set.of("ONLINE", "OFFLINE");

	private final SceneRuleMapper ruleMapper;

	public DslValidator(SceneRuleMapper ruleMapper) {
		this.ruleMapper = ruleMapper;
	}

	/**
	 * 校验规则配置，非法时抛出 IllegalArgumentException（message 供前端展示）。
	 */
	public void validate(RuleConfig config, Long selfRuleId) {
		if (config == null) {
			throw new IllegalArgumentException("规则配置不能为空");
		}
		validateTriggers(config.getTriggers());
		validateConditions(config.getConditions());
		validateActions(config.getActions(), selfRuleId);
		if (config.getRecovery() != null) {
			validateRecovery(config.getRecovery(), selfRuleId);
		}
	}

	private void validateTriggers(List<RuleTrigger> triggers) {
		if (triggers == null || triggers.isEmpty()) {
			throw new IllegalArgumentException("triggers 至少需要 1 个触发器");
		}
		for (RuleTrigger t : triggers) {
			if (t.getType() == null || !TRIGGER_TYPES.contains(t.getType())) {
				throw new IllegalArgumentException("非法触发器类型: " + t.getType());
			}
			switch (t.getType()) {
				case "PROPERTY" -> {
					requireDevice(t.getDevice());
					requireNotBlank(t.getProperty(), "PROPERTY 触发器缺少 property");
					requireOp(t.getOp());
					requireValue(t.getValue());
				}
				case "TIMER" -> requireCron(t.getCron());
				case "LIFECYCLE" -> {
					if (t.getEvent() == null || !LIFECYCLE_EVENTS.contains(t.getEvent())) {
						throw new IllegalArgumentException("LIFECYCLE 触发器 event 必须为 ONLINE/OFFLINE");
					}
				}
				case "ALARM" -> {
					if (t.getState() == null || !ALARM_STATES.contains(t.getState())) {
						throw new IllegalArgumentException("ALARM 触发器 state 必须为 ACTIVE/RECOVER");
					}
					if (t.getLevel() != null && (t.getLevel() < 1 || t.getLevel() > 4)) {
						throw new IllegalArgumentException("ALARM 触发器 level 必须在 1~4 之间");
					}
				}
				case "MANUAL" -> {
					// 手动触发无附加字段
				}
				default -> throw new IllegalArgumentException("未支持的触发器类型: " + t.getType());
			}
		}
	}

	private void validateConditions(List<RuleCondition> conditions) {
		if (conditions == null || conditions.isEmpty()) {
			return; // 空=恒真
		}
		for (RuleCondition c : conditions) {
			if (c.getType() == null || !CONDITION_TYPES.contains(c.getType())) {
				throw new IllegalArgumentException("非法条件类型: " + c.getType());
			}
			switch (c.getType()) {
				case "DEVICE_STATUS" -> {
					requireDevice(c.getDevice());
					if (c.getStatus() == null || !DEVICE_STATUSES.contains(c.getStatus())) {
						throw new IllegalArgumentException("DEVICE_STATUS 条件 status 必须为 ONLINE/OFFLINE");
					}
				}
				case "TIME_RANGE" -> requireTimeRange(c.getStart(), c.getEnd());
				case "PROPERTY" -> {
					requireDevice(c.getDevice());
					requireNotBlank(c.getProperty(), "PROPERTY 条件缺少 property");
					requireOp(c.getOp());
					requireValue(c.getValue());
				}
				default -> throw new IllegalArgumentException("未支持的条件类型: " + c.getType());
			}
		}
	}

	private void validateActions(List<RuleAction> actions, Long selfRuleId) {
		if (actions == null || actions.isEmpty()) {
			throw new IllegalArgumentException("actions 至少需要 1 个动作");
		}
		for (RuleAction a : actions) {
			if (a.getType() == null || !ACTION_TYPES.contains(a.getType())) {
				throw new IllegalArgumentException("非法动作类型: " + a.getType());
			}
			switch (a.getType()) {
				case "DEVICE_COMMAND" -> {
					requireDevice(a.getDevice());
					requireNotBlank(a.getCommand(), "DEVICE_COMMAND 动作缺少 command（物模型服务标识）");
				}
				case "ALARM" -> requireNotBlank(a.getRuleCode(), "ALARM 动作缺少 ruleCode");
				case "NOTIFY" -> {
					if (a.getUrl() == null || a.getUrl().isBlank()) {
						throw new IllegalArgumentException("NOTIFY 动作缺少 url");
					}
					if (a.getChannel() != null && !"WEBHOOK".equalsIgnoreCase(a.getChannel())) {
						throw new IllegalArgumentException("NOTIFY 动作 channel 当前仅支持 WEBHOOK");
					}
				}
				case "RULE" -> validateNestedRule(a.getRuleId(), selfRuleId);
				default -> throw new IllegalArgumentException("未支持的动作类型: " + a.getType());
			}
		}
	}

	private void validateRecovery(RuleRecovery recovery, Long selfRuleId) {
		requireNotBlank(recovery.getProperty(), "recovery 缺少 property");
		requireOp(recovery.getOp());
		requireValue(recovery.getValue());
		validateActions(recovery.getActions(), selfRuleId);
	}

	private void validateNestedRule(Long targetRuleId, Long selfRuleId) {
		if (targetRuleId == null) {
			throw new IllegalArgumentException("RULE 嵌套动作缺少 ruleId");
		}
		if (selfRuleId != null && targetRuleId.equals(selfRuleId)) {
			throw new IllegalArgumentException("RULE 嵌套动作不能指向规则自身（防环）");
		}
		// 目标规则须存在（新增时 selfRuleId 为 null，目标按全局存在性校验）
		if (ruleMapper.selectById(targetRuleId) == null) {
			throw new IllegalArgumentException("RULE 嵌套动作目标规则不存在: " + targetRuleId);
		}
	}

	private void requireDevice(RuleDevice device) {
		if (device == null || device.getProductKey() == null || device.getProductKey().isBlank()) {
			throw new IllegalArgumentException("缺少 device.productKey");
		}
	}

	private void requireOp(String op) {
		if (op == null || !OPS.contains(op)) {
			throw new IllegalArgumentException("比较操作符必须为 GT/GTE/LT/LTE/EQ/NEQ: " + op);
		}
	}

	private void requireValue(Object value) {
		if (value == null) {
			throw new IllegalArgumentException("缺少比较阈值 value");
		}
	}

	private void requireCron(String cron) {
		if (cron == null || !CronValidator.isValid(cron)) {
			throw new IllegalArgumentException("TIMER 触发器 cron 非法（需 6 位：秒 分 时 日 月 周）: " + cron);
		}
	}

	private void requireTimeRange(String start, String end) {
		if (start == null || end == null || !start.matches("\\d{2}:\\d{2}") || !end.matches("\\d{2}:\\d{2}")) {
			throw new IllegalArgumentException("TIME_RANGE 条件 start/end 必须为 HH:mm 格式");
		}
	}

	private void requireNotBlank(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
	}

}
