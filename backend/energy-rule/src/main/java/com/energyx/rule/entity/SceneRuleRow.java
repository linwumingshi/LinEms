package com.energyx.rule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_scene_rule 行投影（trigger/condition/action/recovery 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
@TableName("iot_scene_rule")
public class SceneRuleRow {

	/** 规则 ID（自增主键） */
	@TableId(type = IdType.AUTO)
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
