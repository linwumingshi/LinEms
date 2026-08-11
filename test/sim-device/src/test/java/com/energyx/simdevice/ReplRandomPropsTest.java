package com.energyx.simdevice;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品感知随机属性集：METER 只报 importPower（物模型字段，ModelValidator 校验才放行），
 * 其余按 PCS 六字段。分布与 test/stress ThroughputLoad 一致。
 */
class ReplRandomPropsTest {

    @Test
    void meterReportsOnlyImportPower() {
        Map<String, Object> props = Repl.randomProps("snd_ess_meter", new Random(1));
        assertEquals(Map.of("importPower", props.get("importPower")), props);
        Number v = (Number) props.get("importPower");
        assertTrue(v.intValue() >= 500 && v.intValue() < 3500);
    }

    @Test
    void pcsReportsSixFields() {
        Map<String, Object> props = Repl.randomProps("snd_ess_pcs", new Random(2));
        assertEquals(6, props.size());
        assertFalse(props.containsKey("importPower"));
        assertTrue(props.containsKey("power"));
        assertTrue(props.containsKey("runMode"));
    }

    @Test
    void meterRangeIsDeterministicForFixedSeed() {
        assertEquals(Repl.randomProps("snd_ess_meter", new Random(7)),
                Repl.randomProps("snd_ess_meter", new Random(7)));
    }
}
