package com.energyx.rule.web.dto;

import com.energyx.rule.model.RuleConfig;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场景联动规则视图（详情/分页返回）。
 */
@Data
public class RuleView {

	private Long ruleId;

	private Long tenantId;

	private String ruleCode;

	private String ruleName;

	private String description;

	/** DSL 版本 */
	private Integer dslVersion;

	/** TCA 配置（触发/条件/动作） */
	private RuleConfig dsl;

	/** 动作防抖窗口（秒） */
	private Integer debounceSeconds;

	/** 优先级 */
	private Integer priority;

	/** 0停用 1启用 */
	private Integer enabled;

	/** 乐观锁版本 */
	private Integer version;

	private Long createBy;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

}
