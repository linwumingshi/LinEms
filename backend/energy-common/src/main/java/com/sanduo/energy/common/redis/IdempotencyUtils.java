package com.sanduo.energy.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 幂等工具：SETNX 实现"一次性"语义。
 * 用于指令去重（commandId）、消息去重（messageId）、影子 delta 幂等（Phase 6）。
 */
@Slf4j
@Component
public class IdempotencyUtils {

    /** 对齐 Redis-key规范 §3.3：命令幂等键 iot:cmd:idem:{command_id} */
    private static final String PREFIX = "iot:cmd:idem:";

    private final StringRedisTemplate redis;

    public IdempotencyUtils(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取幂等许可。
     *
     * @param key         幂等键，如 commandId
     * @param ttlSeconds  有效窗口
     * @return true=首次（可继续处理）；false=重复（应幂等返回）
     */
    public boolean tryAcquire(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(PREFIX + key, "1", Duration.ofSeconds(ttlSeconds)));
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + key));
    }

    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
