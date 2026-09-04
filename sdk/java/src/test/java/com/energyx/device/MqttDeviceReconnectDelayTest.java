package com.energyx.device;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重连延迟半随机抖动验证：延迟必须落在 [capped/2, capped] 且同档位下有分散。
 */
class MqttDeviceReconnectDelayTest {

    /**
     * 退避上限内所有档位的延迟都须落在 [capped/2, capped]。
     */
    @Test
    void delayInHalfRange() {
        for (int attempt = 0; attempt <= 8; attempt++) {
            long capped = Math.min(1_000L * (1L << Math.min(attempt, 5)), 60_000L);
            for (int i = 0; i < 200; i++) {
                long delay = MqttDevice.reconnectDelayMillis(1_000, 60_000, attempt);
                assertTrue(delay >= capped / 2 && delay <= capped,
                        "attempt=" + attempt + " delay=" + delay + " 须落在 [" + (capped / 2) + ", " + capped + "]");
            }
        }
    }

    /**
     * 同一档位重复采样必须出现多个不同值，证明抖动真实生效而非恒等延迟。
     */
    @Test
    void delaySpreads() {
        Set<Long> samples = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            samples.add(MqttDevice.reconnectDelayMillis(1_000, 60_000, 3));
        }
        assertTrue(samples.size() > 1, "同一档位 200 次采样应有多个不同延迟");
    }

    /**
     * 退避上限为 0 时必须安全返回 0（无除零/越界）。
     */
    @Test
    void zeroBackoffSafe() {
        assertEquals(0, MqttDevice.reconnectDelayMillis(0, 0, 0));
    }

}
