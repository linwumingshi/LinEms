package com.energyx.alarm.web.dto;

import com.energyx.common.enums.AlarmLevel;
import com.energyx.common.enums.AlarmRecordStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警记录视图（分页查询返回）。
 */
@Data
public class AlarmRecordView {

	/** 告警事件ID（雪花主键，全局唯一） */
	private String alarmEventId;

	/** 租户ID */
	private Long tenantId;

	/** 设备ID */
	private Long deviceId;

	/** 产品标识（productKey，可空） */
	private String productKey;

	/** 触发该告警的规则ID（场景联动告警为 0） */
	private Long ruleId;

	/** 规则编码（场景联动告警为场景编码） */
	private String ruleCode;

	/**
	 * 告警级别，见 {@link AlarmLevel}（PROMPT/GENERAL/SERIOUS/CRITICAL，对应 DB 1提示/2一般/3严重/4危急）
	 */
	private AlarmLevel level;

	/** 触发类型：1属性比较 2事件 3策略（场景联动） */
	private Integer type;

	/** 告警记录状态，见 {@link AlarmRecordStatus}（ACTIVE/RECOVERED/ACKED，对应 DB 0触发中/1已恢复/2已确认） */
	private AlarmRecordStatus status;

	/** 状态名枚举字符串：ACTIVE / RECOVERED / ACKED */
	private String statusName;

	/** 告警内容描述 */
	private String message;

	/** 扩展字段（原样存储的额外上下文，如 metric/currentValue/event 等） */
	private Map<String, Object> ext = new LinkedHashMap<>();

	/** 触发时间 */
	private LocalDateTime triggeredTime;

	/** 恢复时间（未恢复为空） */
	private LocalDateTime recoveredTime;

	/** 确认人（工号/账号，未确认为空） */
	private String ackedBy;

	/** 确认时间（未确认为空） */
	private LocalDateTime ackTime;

}
