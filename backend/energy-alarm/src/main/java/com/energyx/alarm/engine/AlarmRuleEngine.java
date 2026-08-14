package com.energyx.alarm.engine;

import com.energyx.alarm.model.AlarmCondition;
import com.energyx.common.util.ValueCompareUtils;

/**
 * 告警规则纯计算引擎（无状态、无 IO、可单测）。
 *
 * <ul>
 * <li>属性规则：对上报属性值与阈值做 op 比较（数值优先，非数值 EQ/NEQ 走字符串）；</li>
 * <li>事件规则：事件标识精确匹配；</li>
 * <li>恢复规则：同属性比较（值回到正常区间即恢复）。</li>
 * </ul>
 *
 * <p>
 * 比较语义（委托 {@link ValueCompareUtils}，与规则引擎共用）：GT/GTE/LT/LTE
 * 仅接受数值（字符串可解析为数值时也参与比较，解析失败视为不满足）； EQ/NEQ 两端均可解析为数值时按数值比较，否则按字符串比较。
 * </p>
 */
public final class AlarmRuleEngine {

	private AlarmRuleEngine() {
	}

	/** 属性规则触发判断：metric 命中且 op 比较成立 */
	public static boolean propertyMet(AlarmCondition c, Object current) {
		return c != null && c.getMetric() != null && c.getOp() != null && c.getValue() != null
				&& compare(c.getOp(), current, c.getValue());
	}

	/** 恢复条件判断（属性恢复规则） */
	public static boolean recoveryMet(AlarmCondition c, Object current) {
		return propertyMet(c, current);
	}

	/** 事件规则判断：event 标识相等 */
	public static boolean eventMet(AlarmCondition c, String eventName) {
		return c != null && c.getEvent() != null && c.getEvent().equals(eventName);
	}

	/** 通用比较（委托 ValueCompareUtils；返回 false 表示不满足/无法比较） */
	public static boolean compare(String op, Object current, Object threshold) {
		return ValueCompareUtils.compare(op, current, threshold);
	}

}
