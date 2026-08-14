package com.energyx.rule.engine;

import com.energyx.common.util.ValueCompareUtils;
import com.energyx.rule.util.RuleRedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 恢复边沿触发跟踪器（Phase 11 设计 §7.5）：属性触发规则从「满足」回到「不满足」时触发恢复动作。
 *
 * <p>
 * 状态机（rule:state:{ruleId}:{deviceId}）：
 * <ul>
 * <li>首次条件满足：置 FIRED，执行主动作；</li>
 * <li>持续满足：防抖窗口控制（不重复执行主动作）；</li>
 * <li>条件从满足 → 不满足：置 RECOVERED，执行恢复动作（恢复动作不受防抖限制）；</li>
 * <li>持续不满足：保持 RECOVERED，不重复触发恢复。</li>
 * </ul>
 * </p>
 */
@Component
public class RecoveryTracker {

	/** 状态值：已触发 */
	private static final String FIRED = "FIRED";

	/** 状态值：已恢复 */
	private static final String RECOVERED = "RECOVERED";

	private final StringRedisTemplate redis;

	public RecoveryTracker(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 边沿判定：当前条件满足时是否应执行主动作（首次满足才执行，持续满足由防抖控制）。
	 * @return true 表示从 RECOVERED/无状态 → FIRED 的上升沿，应执行主动作
	 */
	public boolean shouldFire(Long ruleId, long deviceId, boolean conditionMet) {
		String key = RuleRedisKeys.state(ruleId, deviceId);
		if (!conditionMet) {
			// 条件不满足：若之前是 FIRED（下降沿）置 RECOVERED，返回 false（由调用方触发恢复动作）
			String prev = redis.opsForValue().get(key);
			if (FIRED.equals(prev)) {
				redis.opsForValue().set(key, RECOVERED);
			}
			return false;
		}
		// 条件满足：无状态或已恢复 → 上升沿（置 FIRED 返回 true）；已 FIRED → 持续满足（false，防抖兜底）
		String prev = redis.opsForValue().get(key);
		if (FIRED.equals(prev)) {
			return false;
		}
		redis.opsForValue().set(key, FIRED);
		return true;
	}

	/**
	 * 恢复边沿判定：条件从「满足」刚回到「不满足」时应执行恢复动作。
	 * @return true 表示 FIRED → RECOVERED 的下降沿，应执行恢复动作
	 */
	public boolean shouldRecover(Long ruleId, long deviceId, boolean conditionMet) {
		String key = RuleRedisKeys.state(ruleId, deviceId);
		if (conditionMet) {
			return false;
		}
		String prev = redis.opsForValue().get(key);
		if (!FIRED.equals(prev)) {
			return false;
		}
		redis.opsForValue().set(key, RECOVERED);
		return true;
	}

	/** 恢复条件本身比较（属性值回到正常区间） */
	public static boolean recoveryMet(String op, Object current, Object threshold) {
		return ValueCompareUtils.compare(op, current, threshold);
	}

	/** 清理状态 key（规则停用/删除时） */
	public void clear(Long ruleId, long deviceId) {
		if (ruleId == null) {
			return;
		}
		try {
			redis.delete(RuleRedisKeys.state(ruleId, deviceId));
		}
		catch (Exception e) {
			// 清理失败无害
		}
	}

}
