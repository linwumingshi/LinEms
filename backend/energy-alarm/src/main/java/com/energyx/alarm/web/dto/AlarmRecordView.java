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

	private String alarmEventId;

	private Long tenantId;

	private Long deviceId;

	private String productKey;

	private Long ruleId;

	private String ruleCode;

	/** 告警级别（PROMPT/GENERAL/SERIOUS/CRITICAL，对应 DB 1提示 2一般 3严重 4危急） */
	private AlarmLevel level;

	/** 1属性 2事件 3策略 */
	private Integer type;

	/** 告警记录状态（ACTIVE/RECOVERED/ACKED，对应 DB 0触发中 1已恢复 2已确认） */
	private AlarmRecordStatus status;

	/** 状态名 ACTIVE / RECOVERED / ACKED */
	private String statusName;

	private String message;

	private Map<String, Object> ext = new LinkedHashMap<>();

	private LocalDateTime triggeredTime;

	private LocalDateTime recoveredTime;

	private String ackedBy;

	private LocalDateTime ackTime;

}
