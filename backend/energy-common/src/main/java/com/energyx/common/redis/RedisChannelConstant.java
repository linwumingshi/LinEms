package com.energyx.common.redis;

/**
 * Redis 通道常量（pub/sub 跨模块约定）。
 *
 * <p>
 * 规则：新增通道必须先补 docs/design/Redis-key规范.md 再编码；通道常量放本类统一出口， 禁止各模块散落字符串拼接。
 * </p>
 */
public final class RedisChannelConstant {

	/** 凭据失效广播：消息体 = clientId（MQTT 认证缓存驱逐，broker 侧订阅删除 cache:cred 并踢线） */
	public static final String CREDENTIAL_REVOKED = "mqtt:cred:revoked";

	/** 场景规则变更广播：消息体 = {ruleId} 或 ALL（rule 服务多实例热更新，Phase 11） */
	public static final String RULE_CHANGED = "rule:changed";

	private RedisChannelConstant() {
	}

}
