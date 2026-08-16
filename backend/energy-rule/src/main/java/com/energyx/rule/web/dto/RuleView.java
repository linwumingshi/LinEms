package com.energyx.rule.web.dto;

import com.energyx.rule.model.RuleConfig;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场景联动规则视图（详情/分页返回）。
 */
@Data
public class RuleView {

	/**
	 * 规则 ID（主键）。
	 */
	private Long ruleId;

	/**
	 * 租户 ID。
	 */
	private Long tenantId;

	/**
	 * 规则编码（唯一，租户内）。
	 */
	private String ruleCode;

	/**
	 * 规则名称。
	 */
	private String ruleName;

	/**
	 * 规则描述。
	 */
	private String description;

	/**
	 * DSL 版本。
	 */
	private Integer dslVersion;

	/**
	 * TCA 配置（触发/条件/动作），字段说明见 {@link RuleConfig}。
	 */
	private RuleConfig dsl;

	/**
	 * 动作防抖窗口（秒）。
	 */
	private Integer debounceSeconds;

	/**
	 * 优先级（数值越小越优先）。
	 */
	private Integer priority;

	/**
	 * 启用状态，0=停用 1=启用。
	 */
	private Integer enabled;

	/**
	 * 乐观锁版本。
	 */
	private Integer version;

	/**
	 * 创建人（0=系统）。
	 */
	private Long createBy;

	/**
	 * 创建时间。
	 */
	private LocalDateTime createTime;

	/**
	 * 更新时间。
	 */
	private LocalDateTime updateTime;

}
