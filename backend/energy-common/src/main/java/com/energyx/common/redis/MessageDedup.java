package com.energyx.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消息幂等去重（跨消费边界），对应 Redis-key规范 `iot:msg:dedup:{stage}:{device_id}:{message_id}`。
 *
 * <p>为什么按 stage 区分命名空间：一条设备报文会顺序经过多个消费边界（access 摄入 → tsdb 落库 →
 * shadow/rule 处理），每个边界的 Kafka 交付都可能各自重放。若共用同一 key，上游边界一旦消费过，
 * 下游边界会把合法消息误判为重复而丢弃。因此每个边界独立 SETNX、独立 TTL。</p>
 *
 * <p>用法：{@code messageDedup.tryOnce("access", deviceId, messageId, 300)} 返回 true=首次，可继续；false=重复。</p>
 */
@Slf4j
@Component
public class MessageDedup {

    private final StringRedisTemplate redis;

    public MessageDedup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取某边界的一次性处理许可。
     *
     * @param stage       消费边界：access / tsdb / shadow / rule / alarm / ws
     * @param deviceId    设备 ID
     * @param messageId   消息 ID
     * @param ttlSeconds  有效窗口（秒）
     * @return true=首次（可继续处理）；false=该边界已处理过（应幂等返回）
     */
    public boolean tryOnce(String stage, long deviceId, String messageId, long ttlSeconds) {
        String key = "iot:msg:dedup:" + stage + ":" + deviceId + ":" + messageId;
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds)));
    }
}
