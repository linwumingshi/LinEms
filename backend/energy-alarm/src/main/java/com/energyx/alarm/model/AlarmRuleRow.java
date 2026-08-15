package com.energyx.alarm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.AlarmLevel;
import com.energyx.common.enums.AlarmRuleStatus;
import com.energyx.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * iot_alarm_rule 行投影（condition/recovery 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iot_alarm_rule")
public class AlarmRuleRow extends BaseEntity {

	/** 规则ID（雪花，MyBatis-Plus ASSIGN_ID 自动生成） */
	@TableId(type = IdType.ASSIGN_ID)
	private Long ruleId;

	/** 创建人（用户 ID，系统动作填 0；表含 create_by 列） */
	private Long createBy;

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

}
