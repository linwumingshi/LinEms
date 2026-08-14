package com.energyx.rule.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 恢复边沿触发跟踪器测试（FIRED 上升沿 / 持续满足 / RECOVERED 下降沿 / 恢复条件比较）。
 */
class RecoveryTrackerTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final RecoveryTracker tracker;

	RecoveryTrackerTest() {
		when(redis.opsForValue()).thenReturn(ops);
		tracker = new RecoveryTracker(redis);
	}

	@Test
	@DisplayName("shouldFire：无状态首次满足 → true（上升沿）")
	void firstFire() {
		when(ops.get(anyString())).thenReturn(null);
		assertTrue(tracker.shouldFire(1L, 10L, true));
	}

	@Test
	@DisplayName("shouldFire：已 FIRED 持续满足 → false（由防抖兜底）")
	void sustainedFire() {
		when(ops.get(anyString())).thenReturn("FIRED");
		assertFalse(tracker.shouldFire(1L, 10L, true));
	}

	@Test
	@DisplayName("shouldFire：条件不满足 → false 并置 RECOVERED")
	void conditionNotMet() {
		when(ops.get(anyString())).thenReturn("FIRED");
		assertFalse(tracker.shouldFire(1L, 10L, false));
	}

	@Test
	@DisplayName("shouldRecover：FIRED → 条件不满足 → true（下降沿）")
	void recoverOnDownEdge() {
		when(ops.get(anyString())).thenReturn("FIRED");
		assertTrue(tracker.shouldRecover(1L, 10L, false));
	}

	@Test
	@DisplayName("shouldRecover：已 RECOVERED 再次不满足 → false（不重复恢复）")
	void noDoubleRecover() {
		when(ops.get(anyString())).thenReturn("RECOVERED");
		assertFalse(tracker.shouldRecover(1L, 10L, false));
	}

	@Test
	@DisplayName("shouldRecover：条件仍满足 → false")
	void stillMetNoRecover() {
		assertFalse(tracker.shouldRecover(1L, 10L, true));
	}

	@Test
	@DisplayName("recoveryMet：温度 43 LTE 45 恢复条件成立")
	void recoveryMetCompare() {
		assertTrue(RecoveryTracker.recoveryMet("LTE", 43, 45));
		assertFalse(RecoveryTracker.recoveryMet("LTE", 47, 45));
	}

}
