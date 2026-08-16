package com.energyx.alarm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 场景联动触发告警请求体（Phase 11：RuleEngine ALARM 动作调用 POST /api/alarm/trigger）。
 */
@Data
public class SceneAlarmRequest {

	/**
	 * 设备ID。
	 */
	@NotNull(message = "deviceId 不能为空")
	private Long deviceId;

	/** 产品标识（可空） */
	private String productKey;

	/** 场景告警编码，如 SCENE_TEMP_HIGH */
	@NotBlank(message = "ruleCode 不能为空")
	private String ruleCode;

	/** 告警级别 1提示 2一般 3严重 4危急，缺省 3（严重） */
	private Integer severity;

	/** 告警内容（可空，缺省取 ruleCode） */
	private String message;

	/** 扩展字段（可空，原样存入告警 ext） */
	private java.util.Map<String, Object> ext;

}
