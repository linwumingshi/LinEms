package com.energyx.shadow.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_shadow 行投影（reported/desired 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
public class ShadowRow {

    private Long deviceId;
    private Long tenantId;
    private String reported;
    private String desired;
    private Integer version;
    private LocalDateTime lastReportedTime;
    private LocalDateTime lastDesiredTime;
}
