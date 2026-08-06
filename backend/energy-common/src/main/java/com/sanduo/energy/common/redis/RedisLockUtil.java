package com.sanduo.energy.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 简易分布式锁（SETNX + Lua 释放）。
 * 说明：生产可升级 Redisson（看门狗续期/可重入），此实现用于 Phase 3~5 阶段。
 */
@Component
public class RedisLockUtil {

    private static final String PREFIX = "lock:";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;

    public RedisLockUtil(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 尝试加锁，返回释放用的 requestId；null 表示加锁失败 */
    public String tryLock(String key, long ttlSeconds) {
        String requestId = UUID.randomUUID().toString();
        boolean ok = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(PREFIX + key, requestId, Duration.ofSeconds(ttlSeconds)));
        return ok ? requestId : null;
    }

    /** 释放锁（仅当 requestId 匹配） */
    public void unlock(String key, String requestId) {
        redis.execute(new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
                List.of(PREFIX + key), requestId);
    }
}
