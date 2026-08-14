package com.energyx.rule.model;

import lombok.Data;

import java.util.Map;

/**
 * 规则执行动作（actions[] 元素，多动作独立执行，单个失败不影响其他）。
 *
 * <p>
 * 类型字段：
 * <ul>
 * <li>DEVICE_COMMAND：设备控制命令 —— device + command（物模型服务标识）+ params +
 * timeoutMs/maxRetry（可选）；</li>
 * <li>ALARM：触发告警 —— ruleCode + severity + message（调告警中心 POST /alarm/trigger）；</li>
 * <li>NOTIFY：外部通知 —— channel=WEBHOOK + url + headers + template（模板变量 ${property.xxx}
 * 渲染）；</li>
 * <li>RULE：嵌套规则 —— ruleId（跳转目标规则，跳过其 Trigger 直接评估 Condition）。</li>
 * </ul>
 * </p>
 */
@Data
public class RuleAction {

	/** 动作类型：DEVICE_COMMAND/ALARM/NOTIFY/RULE */
	private String type;

	/** 设备引用（DEVICE_COMMAND 使用） */
	private RuleDevice device;

	/** 物模型服务标识，如 setPower / startCharge（DEVICE_COMMAND 必填） */
	private String command;

	/** 命令参数（DEVICE_COMMAND 可选） */
	private Map<String, Object> params;

	/** 命令超时（毫秒，可选，缺省命令中心默认） */
	private Integer timeoutMs;

	/** 命令最大重试（可选，缺省命令中心默认） */
	private Integer maxRetry;

	/** 场景告警编码（ALARM 必填），如 SCENE_TEMP_HIGH */
	private String ruleCode;

	/** 告警级别 1提示 2一般 3严重 4危急（ALARM 可选，缺省 3） */
	private Integer severity;

	/** 告警内容（ALARM 可选，支持模板变量） */
	private String message;

	/** 通知渠道（NOTIFY 必填，当前仅 WEBHOOK） */
	private String channel;

	/** webhook 地址（NOTIFY 必填） */
	private String url;

	/** HTTP 头（NOTIFY 可选，支持模板变量） */
	private Map<String, String> headers;

	/** 消息模板（NOTIFY 可选，支持 ${property.xxx} / ${device.xxx} / ${ts}） */
	private String template;

	/** 嵌套目标规则 ID（RULE 必填） */
	private Long ruleId;

}
