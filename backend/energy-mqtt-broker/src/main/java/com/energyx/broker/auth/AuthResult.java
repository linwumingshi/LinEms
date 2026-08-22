package com.energyx.broker.auth;

import lombok.Getter;

/**
 * 认证结果：放行 / 拒绝（带拒绝原因码，映射 MQTT CONNACK return code）。
 */
@Getter
public final class AuthResult {

	/** 是否放行：true=认证通过，false=拒绝 */
	private final boolean allowed;

	/** 拒绝原因码（MQTT CONNACK 0x01~0x05），允许时为 0 */
	private final int connackCode;

	/** 认证失败计数是否达到封禁阈值（供日志与审计） */
	private final boolean banned;

	/** 放行时携带的设备凭据，拒绝时为 null */
	private final DeviceCredential credential;

	/** 拒绝原因描述（供日志与审计，允许时为 ok） */
	private final String reason;

	/** 私有构造：统一由 {@link #allow} / {@link #deny} 工厂方法创建实例 */
	private AuthResult(boolean allowed, int connackCode, boolean banned, DeviceCredential credential, String reason) {
		this.allowed = allowed;
		this.connackCode = connackCode;
		this.banned = banned;
		this.credential = credential;
		this.reason = reason;
	}

	/** 构造放行结果，携带通过认证的设备凭据 */
	public static AuthResult allow(DeviceCredential credential) {
		return new AuthResult(true, 0, false, credential, "ok");
	}

	/** CONNACK 拒绝码：1=版本不支持 2=标识符非法 3=服务不可用 4=用户名/密码错误 5=未授权 */
	public static AuthResult deny(int connackCode, boolean banned, String reason) {
		return new AuthResult(false, connackCode, banned, null, reason);
	}

}
