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

	/** 规则编码（租户内唯一，创建后不可修改） */
	private String ruleCode;

	/** 规则名称 */
	private String ruleName;

	/** 作用产品（NULL=全局） */
	private Long productId;

	/** 作用设备（NULL=产品级/全局） */
	private Long deviceId;

	/** 触发类型：1属性比较 2事件 3策略（场景联动） */
	private Integer triggerType;

	/**
	 * 触发条件 JSON（AlarmCondition 结构），如
	 * {"metric":"temp","op":"GTE","value":60,"windowSec":60}
	 */
	private String condition;

	/**
	 * 告警级别，见 {@link AlarmLevel}（PROMPT/GENERAL/SERIOUS/CRITICAL，对应 DB 1提示/2一般/3严重/4危急）
	 */
	private AlarmLevel severity;

	/** 静默期（秒），缺省 300；触发后该窗口内同规则+设备不重复告警 */
	private Integer silenceSeconds;

	/** 恢复条件 JSON（AlarmCondition 结构，可空）；缺省无显式恢复，触发条件不再满足即视为恢复 */
	private String recovery;

	/** 告警规则状态，见 {@link AlarmRuleStatus}（DISABLED/ENABLED，对应 DB 0停用/1启用） */
	private AlarmRuleStatus status;

	/** 规则描述（可空） */
	private String description;

}
