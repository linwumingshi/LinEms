package com.energyx.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 分布式锁（R-01：@Scheduled 定时任务多实例互斥）。
 *
 * <p>
 * 实现：Redis SET NX EX（原子）加锁 + Lua 校验 owner 释放（防误删他人锁）。 相比 Redisson
 * 提供可重入/看门狗等完整能力，本组件面向「定时任务单实例执行」场景： 任务本身有明确时长上界，固定 TTL（略大于任务最坏耗时）即可保证互斥，无需看门狗续期。 语义与
 * Redis-key 规范 §4 的 `lock:{resource}`（Redisson 锁）等价，实现为轻量 Lua 原子锁。
 * </p>
 *
 * <p>
 * 使用方式： <pre>
 * if (distributedLock.tryLock("scheduled:command-scan", 60)) {
 *     try { doScan(); } finally { distributedLock.unlock("scheduled:command-scan"); }
 * }
 * // 或便捷方法：
 * distributedLock.runIfAcquired("scheduled:command-scan", 60, this::doScan);
 * </pre>
 * </p>
 */
@Slf4j
@Component
public class DistributedLock {

	/** 本实例唯一 owner：释放锁时校验归属，防止误删其他实例（或上一轮）的锁 */
	private final String owner = UUID.randomUUID().toString();

	/** Lua 释放锁：仅当持有者为当前实例时删除（原子 compare+del） */
	private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end;",
			Long.class);

	private final StringRedisTemplate redis;

	public DistributedLock(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 尝试获取分布式锁（非阻塞）。
	 * @param key 锁标识（建议 "scheduled:{task}"，最终 key 为 "lock:{key}"，见 Redis-key 规范）
	 * @param ttlSeconds 锁有效期（秒）；需大于任务最坏耗时，到期自动释放（防持有者宕机死锁）
	 * @return true 获取成功（调用方必须 finally 释放）；false 已被其他实例持有
	 */
	public boolean tryLock(String key, long ttlSeconds) {
		Boolean ok = redis.opsForValue().setIfAbsent("lock:" + key, owner, Duration.ofSeconds(ttlSeconds));
		if (Boolean.TRUE.equals(ok)) {
			log.debug("[DistributedLock] 获取锁成功 key={}", key);
		}
		return Boolean.TRUE.equals(ok);
	}

	/** 释放分布式锁（仅当持有者为本实例；非持有者调用无副作用） */
	public void unlock(String key) {
		Long result = redis.execute(UNLOCK_SCRIPT, Collections.singletonList("lock:" + key), owner);
		if (Long.valueOf(1L).equals(result)) {
			log.debug("[DistributedLock] 释放锁成功 key={}", key);
		}
	}

	/**
	 * 便捷方法：获取锁成功则执行 action 并在 finally 释放；失败跳过本轮（其他实例在执行）。
	 * @return true 本实例执行了 action；false 锁被占用，本轮跳过
	 */
	public boolean runIfAcquired(String key, long ttlSeconds, Runnable action) {
		if (!tryLock(key, ttlSeconds)) {
			log.debug("[DistributedLock] 锁被占用，跳过本轮 key={}", key);
			return false;
		}
		try {
			action.run();
			return true;
		}
		finally {
			unlock(key);
		}
	}

}
