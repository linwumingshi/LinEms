package com.energyx.rule.web.dto;

import com.energyx.rule.model.RuleConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建/更新场景联动规则请求体。
 *
 * <p>
 * dsl（RuleConfig）为 TCA 模型完整配置（triggers/conditions/actions/recovery）， ruleCode/ruleName
 * 冗余于 dsl 之外便于检索；更新时 version 必填做乐观锁。
 * </p>
 */
@Data
public class SaveRuleRequest {

	/** 规则编码（唯一，租户内），如 SCENE_TEMP_HIGH */
	@NotBlank(message = "ruleCode 不能为空")
	private String ruleCode;

	/** 规则名称 */
	@NotBlank(message = "ruleName 不能为空")
	private String ruleName;

	private String description;

	/** TCA DSL 配置（JSON 反序列化对象） */
	@NotNull(message = "dsl 不能为空")
	private RuleConfig dsl;

	/** 动作防抖窗口（秒），缺省 300 */
	private Integer debounceSeconds;

	/** 优先级（小优先），缺省 100 */
	private Integer priority;

	/** 是否启用，缺省 true */
	private Boolean enabled;

	/** 乐观锁版本（更新时必填） */
	private Integer version;

	/** 创建人（缺省 0=系统） */
	private Long createBy;

}
