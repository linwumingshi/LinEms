package com.energyx.alarm.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 告警规则新增/修改请求体（POST/PUT /api/alarm/rule）。
 *
 * <p>
 * condition / recovery 为 JSON 字符串，结构对齐 {@code AlarmCondition}： 属性规则
 * {@code {"metric":"temp","op":"GTE","value":60,"windowSec":60}}、事件规则
 * {@code {"event":"bmsFault"}}。rule_code 创建后不可修改。
 * </p>
 */
@Data
public class AlarmRuleSaveReq {

	/** 租户（缺省 1，单租户环境；多租户接入 TenantContext 后忽略此字段） */
	private Long tenantId;

	/** 规则编码，租户内唯一，如 ALM_TEMP_HIGH */
	@NotBlank(message = "ruleCode 不能为空")
	private String ruleCode;

	/** 规则名称 */
	@NotBlank(message = "ruleName 不能为空")
	private String ruleName;

	/** 作用产品（NULL=全局） */
	private Long productId;

	/** 作用设备（NULL=产品级） */
	private Long deviceId;

	/** 触发类型：1属性比较 2事件 */
	@NotNull(message = "triggerType 不能为空")
	@Min(value = 1, message = "triggerType 仅支持 1属性比较/2事件")
	@Max(value = 2, message = "triggerType 仅支持 1属性比较/2事件")
	private Integer triggerType;

	/** 触发条件 JSON（AlarmCondition 结构） */
	@NotBlank(message = "condition 不能为空")
	private String condition;

	/** 告警级别 1提示 2一般 3严重 4危急，缺省 3 */
	@Min(value = 1, message = "severity 范围 1-4")
	@Max(value = 4, message = "severity 范围 1-4")
	private Integer severity;

	/** 静默期（秒），缺省 300 */
	@Min(value = 0, message = "silenceSeconds 不能为负")
	private Integer silenceSeconds;

	/** 恢复条件 JSON（可空） */
	private String recovery;

	/** 状态：0停用 1启用，缺省 1 */
	@Min(value = 0, message = "status 仅 0/1")
	@Max(value = 1, message = "status 仅 0/1")
	private Integer status;

	/** 描述（可空） */
	private String description;

}
