package com.energyx.rule.model;

import lombok.Data;

/**
 * 规则触发器（triggers[] 元素，多触发器 OR 关系）。
 *
 * <p>
 * 类型字段：
 * <ul>
 * <li>PROPERTY：设备属性触发 —— device + property + op +
 * value（比较语义与告警引擎一致：GT/GTE/LT/LTE/EQ/NEQ）；</li>
 * <li>TIMER：定时触发 —— cron（6 位，秒 分 时 日 月 周）；</li>
 * <li>LIFECYCLE：设备上下线 —— event=ONLINE/OFFLINE；</li>
 * <li>ALARM：告警触发 —— alarmCode（可空）+ level（可空）+ state=ACTIVE/RECOVER；</li>
 * <li>MANUAL：手动触发 —— 无附加字段，仅由 POST /rule/{id}/trigger 触发。</li>
 * </ul>
 * </p>
 */
@Data
public class RuleTrigger {

	/**
	 * 触发类型：PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL。
	 */
	private String type;

	/**
	 * 设备引用（PROPERTY/LIFECYCLE 使用；LIFECYCLE 可空=全设备），字段说明见 {@link RuleDevice}。
	 */
	private RuleDevice device;

	/**
	 * 属性标识（PROPERTY 必填），如 cellTemp / soc。
	 */
	private String property;

	/**
	 * 比较操作符 GT/GTE/LT/LTE/EQ/NEQ（PROPERTY 必填）。
	 */
	private String op;

	/**
	 * 阈值（PROPERTY 必填，数值或字符串）。
	 */
	private Object value;

	/**
	 * cron 表达式（TIMER 必填，6 位：秒 分 时 日 月 周）。
	 */
	private String cron;

	/**
	 * 生命周期事件：ONLINE/OFFLINE（LIFECYCLE 必填）。
	 */
	private String event;

	/**
	 * 告警码（ALARM 可选）。
	 */
	private String alarmCode;

	/**
	 * 告警级别（ALARM 可选，1=提示 2=一般 3=严重 4=危急）。
	 */
	private Integer level;

	/**
	 * 告警状态：ACTIVE/RECOVER（ALARM 必填）。
	 */
	private String state;

}
