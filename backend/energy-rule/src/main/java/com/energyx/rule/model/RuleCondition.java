package com.energyx.rule.model;

import lombok.Data;

/**
 * 规则执行条件（conditions[] 元素，多条件 AND 关系）。
 *
 * <p>
 * 类型字段：
 * <ul>
 * <li>DEVICE_STATUS：设备在线状态 —— device + status=ONLINE/OFFLINE（读 Redis iot:online:*）；</li>
 * <li>TIME_RANGE：时间范围 —— start/end（HH:mm，支持跨零点）；</li>
 * <li>PROPERTY：属性条件 —— device + property + op + value（优先取触发上下文，其次影子）。</li>
 * </ul>
 * </p>
 */
@Data
public class RuleCondition {

	/**
	 * 条件类型：DEVICE_STATUS/TIME_RANGE/PROPERTY。
	 */
	private String type;

	/**
	 * 设备引用（DEVICE_STATUS/PROPERTY 使用），字段说明见 {@link RuleDevice}。
	 */
	private RuleDevice device;

	/**
	 * 在线状态：ONLINE/OFFLINE（DEVICE_STATUS 必填）。
	 */
	private String status;

	/**
	 * 时间范围起点 HH:mm（TIME_RANGE 必填，支持跨零点）。
	 */
	private String start;

	/**
	 * 时间范围终点 HH:mm（TIME_RANGE 必填，支持跨零点）。
	 */
	private String end;

	/**
	 * 属性标识（PROPERTY 必填）。
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

}
