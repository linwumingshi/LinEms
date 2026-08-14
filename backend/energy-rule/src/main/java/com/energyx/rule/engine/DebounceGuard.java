package com.energyx.rule.engine;

import com.energyx.rule.util.RuleRedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 动作防抖守卫（Phase 11 设计 §7.5）：SETNX rule:debounce:{ruleId}:{deviceId}，窗口期内不重复执行。
 *
 * <p>
 * 同一规则同一设备在 debounceSeconds 窗口内只执行一次动作，防止高频属性上报轰炸 （如温度持续超阈时每秒上报，动作只触发一次）。
 * </p>
 */
@Component
public class DebounceGuard {

	private final StringRedisTemplate redis;

	public DebounceGuard(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 尝试通过防抖（原子 SETNX）。
	 * @param ruleId 规则 ID
	 * @param deviceId 设备 ID（定时/手动触发等无设备场景传 0）
	 * @param debounceSeconds 防抖窗口（秒），&lt;=0 视为不防抖直接放行
	 * @return true 通过（可执行动作）；false 窗口期内已执行过
	 */
	public boolean tryPass(Long ruleId, long deviceId, int debounceSeconds) {
		if (ruleId == null) {
			return true;
		}
		if (debounceSeconds <= 0) {
			return true;
		}
		Boolean ok = redis.opsForValue()
			.setIfAbsent(RuleRedisKeys.debounce(ruleId, deviceId), "1", Duration.ofSeconds(debounceSeconds));
		return Boolean.TRUE.equals(ok);
	}

	/** 清除防抖标记（规则停用/删除时清理，避免残留 key 占内存） */
	public void clear(Long ruleId, long deviceId) {
		if (ruleId == null) {
			return;
		}
		try {
			redis.delete(RuleRedisKeys.debounce(ruleId, deviceId));
		}
		catch (Exception e) {
			// 清理失败无害，TTL 兜底
		}
	}

}
