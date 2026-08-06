package com.sanduo.energy.alarm.web.dto;

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
    /** 1提示 2一般 3严重 4危急 */
    private Integer level;
    /** 1属性 2事件 3策略 */
    private Integer type;
    /** 0触发中 1已恢复 2已确认 */
    private Integer status;
    /** 状态名 ACTIVE / RECOVERED / ACKED */
    private String statusName;
    private String message;
    private Map<String, Object> ext = new LinkedHashMap<>();
    private LocalDateTime triggeredTime;
    private LocalDateTime recoveredTime;
    private String ackedBy;
    private LocalDateTime ackTime;
}
