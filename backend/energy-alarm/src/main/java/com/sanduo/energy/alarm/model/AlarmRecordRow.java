package com.sanduo.energy.alarm.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_alarm_record 行投影（ext 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
public class AlarmRecordRow {

    private String alarmEventId;
    private Long tenantId;
    private Long deviceId;
    private String productKey;
    private Long ruleId;
    private String ruleCode;
    /** 1提示 2一般 3严重 4危急 */
    private Integer level;
    /** 1属性 2事件 3策略 */
    private Integer type;
    /** 0触发中 1已恢复 2已确认 */
    private Integer status;
    private String message;
    private String ext;
    private LocalDateTime triggeredTime;
    private LocalDateTime recoveredTime;
    private String ackedBy;
    private LocalDateTime ackTime;
}
