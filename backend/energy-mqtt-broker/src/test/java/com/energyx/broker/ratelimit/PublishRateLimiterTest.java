package com.energyx.broker.ratelimit;

import com.energyx.broker.config.BrokerProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单设备发布限速（P2-7）正确性测试。
 */
class PublishRateLimiterTest {

    private PublishRateLimiter limiter(int rps, int capacity) {
        BrokerProperties props = new BrokerProperties();
        props.setPublishRateLimitRps(rps);
        props.setRateLimitBucketCapacity(capacity);
        return new PublishRateLimiter(props);
    }

    @Test
    void disabledWhenRpsZero() {
        PublishRateLimiter l = limiter(0, 100);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(l.tryAcquire("pk_dev"), "rps=0 不限制");
        }
    }

    @Test
    void perDeviceIndependentLimit() {
        PublishRateLimiter l = limiter(2, 100);
        assertTrue(l.tryAcquire("pk_dev1"));
        assertTrue(l.tryAcquire("pk_dev1"));
        assertFalse(l.tryAcquire("pk_dev1"), "第 3 次同窗口超限");
        // 其他设备不受影响
        assertTrue(l.tryAcquire("pk_dev2"));
        assertTrue(l.tryAcquire("pk_dev2"));
    }

    @Test
    void windowSlidesBySecond() throws InterruptedException {
        PublishRateLimiter l = limiter(1, 100);
        assertTrue(l.tryAcquire("pk_dev"));
        assertFalse(l.tryAcquire("pk_dev"));
        // 窗口按整秒对齐，跨窗口自动重置；sleep 到下一个窗口（最多等 1.1s）
        Thread.sleep(1_100);
        assertTrue(l.tryAcquire("pk_dev"), "新窗口应恢复配额");
    }

    @Test
    void bucketCapacityCapsMemory() {
        // 容量封顶：超过 capacity 后整体清空降级（不 OOM），随后新设备可继续放行
        PublishRateLimiter l = limiter(1, 2);
        assertTrue(l.tryAcquire("d1"));
        assertTrue(l.tryAcquire("d2"));
        assertTrue(l.tryAcquire("d3"), "超容量清空后新设备放行");
    }
}
