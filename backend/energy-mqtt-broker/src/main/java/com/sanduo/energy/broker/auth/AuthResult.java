package com.sanduo.energy.broker.auth;

import lombok.Getter;

/**
 * 认证结果：放行 / 拒绝（带拒绝原因码，映射 MQTT CONNACK return code）。
 */
@Getter
public final class AuthResult {

    private final boolean allowed;
    /** 拒绝原因码（MQTT CONNACK 0x01~0x05），允许时为 0 */
    private final int connackCode;
    /** 认证失败计数是否达到封禁阈值（供日志与审计） */
    private final boolean banned;
    private final DeviceCredential credential;
    private final String reason;

    private AuthResult(boolean allowed, int connackCode, boolean banned,
                       DeviceCredential credential, String reason) {
        this.allowed = allowed;
        this.connackCode = connackCode;
        this.banned = banned;
        this.credential = credential;
        this.reason = reason;
    }

    public static AuthResult allow(DeviceCredential credential) {
        return new AuthResult(true, 0, false, credential, "ok");
    }

    /** CONNACK 拒绝码：1=版本不支持 2=标识符非法 3=服务不可用 4=用户名/密码错误 5=未授权 */
    public static AuthResult deny(int connackCode, boolean banned, String reason) {
        return new AuthResult(false, connackCode, banned, null, reason);
    }
}
