package com.energyx.common.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁（R-01）正确性测试：加锁/释放/占用跳过。
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockTest {

	@Mock
	private StringRedisTemplate redis;

	@Mock
	private ValueOperations<String, String> valueOps;

	private DistributedLock newLock() {
		when(redis.opsForValue()).thenReturn(valueOps);
		return new DistributedLock(redis);
	}

	@Test
	void tryLockSuccessAndUnlock() {
		DistributedLock lock = newLock();
		when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
		assertTrue(lock.tryLock("scheduled:test", 60));
		lock.unlock("scheduled:test");
		// 释放走 Lua 脚本（compare+del），非直接 delete
		verify(redis).execute(any(), eq(List.of("lock:scheduled:test")), anyString());
	}

	@Test
	void tryLockHeldByOther() {
		DistributedLock lock = newLock();
		when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(Duration.ofSeconds(60))))
			.thenReturn(false);
		assertFalse(lock.tryLock("scheduled:test", 60));
	}

	@Test
	void runIfAcquiredExecutesAction() {
		DistributedLock lock = newLock();
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		AtomicInteger calls = new AtomicInteger();
		boolean executed = lock.runIfAcquired("scheduled:test", 60, calls::incrementAndGet);
		assertTrue(executed);
		assertEquals(1, calls.get());
	}

	@Test
	void runIfAcquiredSkipsWhenLocked() {
		DistributedLock lock = newLock();
		when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		AtomicInteger calls = new AtomicInteger();
		boolean executed = lock.runIfAcquired("scheduled:test", 60, calls::incrementAndGet);
		assertFalse(executed);
		assertEquals(0, calls.get(), "锁被占用时不应执行任务");
		verify(redis, never()).execute(any(), any(), any());
	}

}
