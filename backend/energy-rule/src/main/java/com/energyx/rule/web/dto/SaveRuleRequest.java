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

	/**
	 * 规则编码（唯一，租户内），如 SCENE_TEMP_HIGH。
	 *
	 * @required
	 */
	@NotBlank(message = "ruleCode 不能为空")
	private String ruleCode;

	/**
	 * 规则名称。
	 *
	 * @required
	 */
	@NotBlank(message = "ruleName 不能为空")
	private String ruleName;

	/**
	 * 规则描述（可选）。
	 */
	private String description;

	/**
	 * TCA DSL 配置（JSON 反序列化对象），字段说明见 {@link RuleConfig}。
	 *
	 * @required
	 */
	@NotNull(message = "dsl 不能为空")
	private RuleConfig dsl;

	/**
	 * 动作防抖窗口（秒），缺省 300；同一规则连续命中在该窗口内仅执行一次。
	 */
	private Integer debounceSeconds;

	/**
	 * 优先级（数值越小越优先），缺省 100。
	 */
	private Integer priority;

	/**
	 * 是否启用，缺省 true。
	 */
	private Boolean enabled;

	/**
	 * 乐观锁版本；更新时必填，与库中版本不一致则更新失败。
	 */
	private Integer version;

	/**
	 * 创建人（缺省 0=系统）。
	 */
	private Long createBy;

}
