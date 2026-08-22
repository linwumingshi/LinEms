package com.energyx.broker.auth;

import java.time.LocalDateTime;

/**
 * iot_device_credential 最小查询投影（认证所需字段）。各字段含义：
 * <ul>
 * <li>deviceId：关联设备主键 ID；</li>
 * <li>deviceSecret：设备密钥（HMAC-SHA256 签名密钥，敏感，禁止落日志）；</li>
 * <li>authStatus：凭据状态，1=正常 2=吊销；</li>
 * <li>expireTime：凭据过期时间，null 表示永不过期。</li>
 * </ul>
 */
public record CredentialRow(Long deviceId, String deviceSecret, int authStatus, LocalDateTime expireTime) {
}
