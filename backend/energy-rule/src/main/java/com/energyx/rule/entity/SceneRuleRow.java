package com.energyx.rule.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_scene_rule 行投影（trigger/condition/action/recovery 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
public class SceneRuleRow {

	private Long ruleId;

	private Long tenantId;

	private String ruleCode;

	private String ruleName;

	private String description;

	private Integer dslVersion;

	private String triggerJson;

	private String conditionJson;

	private String actionJson;

	private String recoveryJson;

	private Integer debounceSeconds;

	private Integer priority;

	/** 0停用 1启用 */
	private Integer enabled;

	private Integer version;

	private Long createBy;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

}
