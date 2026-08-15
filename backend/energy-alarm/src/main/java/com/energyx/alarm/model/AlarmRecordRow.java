package com.energyx.alarm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.enums.AlarmLevel;
import com.energyx.common.enums.AlarmRecordStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_alarm_record 行投影（ext 以 JSON 字符串承载，解析在 service 层）。
 *
 * <p>
 * 告警记录为流水表（无 create_time/update_time 列），不继承 BaseEntity，仅声明 {@code @TableName/@TableId} 供
 * MyBatis-Plus BaseMapper 使用。
 * </p>
 */
@Data
@TableName("iot_alarm_record")
public class AlarmRecordRow {

	/** 告警事件 ID（雪花字符串，幂等键） */
	@TableId(type = IdType.INPUT)
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

	private String message;

	private String ext;

	private LocalDateTime triggeredTime;

	private LocalDateTime recoveredTime;

	private String ackedBy;

	private LocalDateTime ackTime;

}
