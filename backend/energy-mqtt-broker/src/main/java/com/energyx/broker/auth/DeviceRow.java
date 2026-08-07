package com.energyx.broker.auth;

/**
 * iot_device 最小查询投影（认证所需字段）。
 */
public record DeviceRow(Long deviceId, Long tenantId, String productKey, String deviceName, int status) {
}
