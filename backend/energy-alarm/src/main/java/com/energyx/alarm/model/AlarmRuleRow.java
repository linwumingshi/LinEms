package com.energyx.alarm.model;

import com.energyx.common.enums.AlarmLevel;
import com.energyx.common.enums.AlarmRuleStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_alarm_rule 行投影（condition/recovery 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
public class AlarmRuleRow {

	private Long ruleId;

	private Long tenantId;

	private String ruleCode;

	private String ruleName;

	/** 作用产品（NULL=全局） */
	private Long productId;

	/** 作用设备（NULL=产品级/全局） */
	private Long deviceId;

	/** 1属性比较 2事件 3策略 */
	private Integer triggerType;

	private String condition;

	/** 告警级别（PROMPT/GENERAL/SERIOUS/CRITICAL，对应 DB 1提示 2一般 3严重 4危急） */
	private AlarmLevel severity;

	/** 静默期（秒），缺省 300 */
	private Integer silenceSeconds;

	private String recovery;

	/** 告警规则状态（DISABLED/ENABLED，对应 DB 0停用 1启用） */
	private AlarmRuleStatus status;

	private String description;

	private Long createBy;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

}
