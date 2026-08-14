package com.energyx.rule.util;

/**
 * 规则域 Redis key 唯一出口（对齐 Redis-key规范 §2 新增 rule:*）。
 */
public final class RuleRedisKeys {

	private RuleRedisKeys() {
	}

	/** 规则缓存（String 规则 JSON，TTL 10min，变更主动删） */
	public static String cache(long ruleId) {
		return "rule:cache:" + ruleId;
	}

	/** 动作防抖（SETNX，TTL=debounceSeconds，窗口内不重复执行） */
	public static String debounce(long ruleId, long deviceId) {
		return "rule:debounce:" + ruleId + ":" + deviceId;
	}

	/** 恢复判定状态（String：FIRED/RECOVERED，边沿触发用） */
	public static String state(long ruleId, long deviceId) {
		return "rule:state:" + ruleId + ":" + deviceId;
	}

	/** 定时触发分布式锁（防多实例重复执行，沿用 lock:scheduled:* 约定） */
	public static String scheduledLock(long ruleId) {
		return "lock:scheduled:rule-" + ruleId;
	}

}
