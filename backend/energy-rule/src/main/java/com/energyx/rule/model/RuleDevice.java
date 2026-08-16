package com.energyx.rule.model;

import lombok.Data;

/**
 * 设备引用（Trigger/Condition/Action 共用）。
 *
 * <p>
 * deviceName 为空表示「产品下全部设备」（仅 Trigger 支持，Condition/Action 必须明确到设备）。
 * </p>
 */
@Data
public class RuleDevice {

	/**
	 * 产品标识（必填）。
	 */
	private String productKey;

	/**
	 * 设备名（可选：Trigger 可空=产品级，Condition/Action 必填）。
	 */
	private String deviceName;

}
