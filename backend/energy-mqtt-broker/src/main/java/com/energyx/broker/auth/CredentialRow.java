package com.energyx.broker.auth;

/**
 * iot_device_credential 最小查询投影（认证所需字段）。
 */
public record CredentialRow(Long deviceId, String deviceSecret, int authStatus,
                            java.time.LocalDateTime expireTime) {
}
