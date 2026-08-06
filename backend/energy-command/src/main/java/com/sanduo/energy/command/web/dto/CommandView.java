package com.sanduo.energy.command.web.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令视图（查询/创建返回）。
 */
@Data
public class CommandView {

    private String commandId;
    private Long tenantId;
    private Long deviceId;
    private String productKey;
    private String command;
    private Integer commandType;
    private Map<String, Object> params = new LinkedHashMap<>();
    private Integer state;
    /** 状态名：CREATED/SENT/DEVICE_RECEIVED/EXECUTING/SUCCESS/FAILED/TIMEOUT */
    private String stateName;
    private Integer retryCount;
    private Integer maxRetry;
    private Integer timeoutMs;
    private LocalDateTime sentTime;
    private LocalDateTime receivedTime;
    private LocalDateTime executingTime;
    private LocalDateTime finishTime;
    private Map<String, Object> result = new LinkedHashMap<>();
    private String errorCode;
    private String errorMsg;
    private Long createBy;
    private LocalDateTime createTime;
}
