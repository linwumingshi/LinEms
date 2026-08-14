package com.energyx.rule.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 防抖守卫测试（SETNX 首过放行 / 窗口内拦截 / 窗口<=0 放行 / ruleId 空放行）。
 */
class DebounceGuardTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final DebounceGuard guard;

	DebounceGuardTest() {
		when(redis.opsForValue()).thenReturn(ops);
		guard = new DebounceGuard(redis);
	}

	@Test
	@DisplayName("首过：SETNX 成功放行")
	void firstPass() {
		when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		assertTrue(guard.tryPass(1L, 10L, 300));
	}

	@Test
	@DisplayName("窗口内：SETNX 失败拦截")
	void withinWindowBlocked() {
		when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		assertFalse(guard.tryPass(1L, 10L, 300));
	}

	@Test
	@DisplayName("debounceSeconds<=0 不防抖直接放行")
	void noDebounce() {
		assertTrue(guard.tryPass(1L, 10L, 0));
		assertTrue(guard.tryPass(1L, 10L, -1));
	}

	@Test
	@DisplayName("ruleId 为空放行（防御）")
	void nullRuleId() {
		assertTrue(guard.tryPass(null, 10L, 300));
	}

}
