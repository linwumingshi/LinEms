package com.energyx.shadow.delta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeltaCalculator 纯函数测试：差异计算与收敛判断。
 */
class DeltaCalculatorTest {

    @Test
    @DisplayName("仅返回与 reported 不一致的属性")
    void compute_onlyDiffersReturned() {
        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("power", 100);
        desired.put("voltage", 720);
        Map<String, Object> reported = new LinkedHashMap<>();
        reported.put("power", 50);
        reported.put("voltage", 720);

        Map<String, Object> delta = DeltaCalculator.compute(desired, reported);

        assertEquals(1, delta.size());
        assertEquals(100, delta.get("power"));
        assertFalse(delta.containsKey("voltage"));
    }

    @Test
    @DisplayName("reported 中多出的属性不进 delta")
    void compute_reportedOnlyKeysIgnored() {
        Map<String, Object> desired = Map.of("power", 100);
        Map<String, Object> reported = Map.of("power", 50, "extra", 1);

        Map<String, Object> delta = DeltaCalculator.compute(desired, reported);

        assertEquals(Map.of("power", 100), delta);
    }

    @Test
    @DisplayName("desired 为空 → delta 为空")
    void compute_emptyDesired() {
        assertTrue(DeltaCalculator.compute(Map.of(), Map.of("power", 50)).isEmpty());
        assertTrue(DeltaCalculator.compute(null, Map.of("power", 50)).isEmpty());
    }

    @Test
    @DisplayName("reported 为 null 视作空 → 全部进 delta")
    void compute_nullReportedTreatsAsEmpty() {
        Map<String, Object> delta = DeltaCalculator.compute(Map.of("power", 100), null);
        assertEquals(Map.of("power", 100), delta);
    }

    @Test
    @DisplayName("全部一致 → delta 空、无需同步")
    void compute_allMatchConverged() {
        Map<String, Object> desired = Map.of("power", 100);
        Map<String, Object> reported = Map.of("power", 100);
        Map<String, Object> delta = DeltaCalculator.compute(desired, reported);
        assertTrue(delta.isEmpty());
        assertFalse(DeltaCalculator.needsSync(delta));
    }

    @Test
    @DisplayName("类型不同视为不一致（50 vs \"50\"）")
    void compute_typeMismatchDiffers() {
        Map<String, Object> delta = DeltaCalculator.compute(
                Map.of("power", 50), Map.of("power", "50"));
        assertEquals(1, delta.size());
    }
}
