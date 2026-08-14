package com.energyx.rule.web.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手动触发请求体（POST /api/rule/{ruleId}/trigger）。
 *
 * <p>
 * payload 任意 JSON（可空），注入 RuleContext.payload 供条件求值/模板渲染引用； 手动触发要求规则含 MANUAL 触发器且处于启用状态。
 * </p>
 */
@Data
public class ManualTriggerRequest {

	/** 手动触发载荷（可空） */
	private Map<String, Object> payload = new LinkedHashMap<>();

}
