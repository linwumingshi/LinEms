package com.energyx.broker.ratelimit;

import com.energyx.broker.config.BrokerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单设备发布速率限制（P2-7，固定窗口令牌桶，本地内存）。
 *
 * <p>设计：
 * <ul>
 *   <li>按 deviceKey 独立计数，窗口 = 1 秒（整秒对齐），窗口切换自动重置；</li>
 *   <li>超限策略由调用方决定：QoS0 直接丢弃（记日志）、QoS1/2 关连接迫使设备节流重连；</li>
 *   <li>容量封顶：桶数量超过 {@code rate-limit-bucket-capacity} 时整体清空降级（拒绝无界增长，
 *       极端攻击下退化为"全放行"而非 OOM）；</li>
 *   <li>本节点内有效，跨节点累计由多节点各自限速叠加（单点语义即本节点接收速率）。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class PublishRateLimiter {

    private final BrokerProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public PublishRateLimiter(BrokerProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试获取一个发布配额。
     *
     * @param deviceKey 设备标识
     * @return true 允许发布；false 超过速率限制
     */
    public boolean tryAcquire(String deviceKey) {
        int rps = properties.getPublishRateLimitRps();
        if (rps <= 0) {
            return true; // 未启用限速
        }
        long windowSec = System.currentTimeMillis() / 1000;
        Bucket bucket = buckets.get(deviceKey);
        if (bucket == null) {
            // 容量封顶：桶数超限整体清空（降级全放行），拒绝无界内存增长
            if (buckets.size() >= properties.getRateLimitBucketCapacity()) {
                buckets.clear();
            }
            bucket = new Bucket();
            Bucket existing = buckets.putIfAbsent(deviceKey, bucket);
            if (existing != null) {
                bucket = existing;
            }
        }
        synchronized (bucket) {
            if (bucket.windowSec != windowSec) {
                bucket.windowSec = windowSec;
                bucket.count = 0;
            }
            if (bucket.count >= rps) {
                return false;
            }
            bucket.count++;
            return true;
        }
    }

    /** 窗口桶：整秒对齐的计数 */
    private static final class Bucket {
        private long windowSec;
        private int count;
    }
}
