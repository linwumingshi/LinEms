package com.sanduo.energy.ems.util;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanGeneratorTest {

    @Test
    void peakValley_basicChargeValleyDischargePeak() {
        PlanInput in = new PlanInput(
            "PEAK_VALLEY",
            """
            {"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}],
             "dischargeWindows":[{"start":"18:00","end":"22:00","powerLimit":80}],
             "socRange":{"min":10,"max":90}}
            """,
            List.of(new PriceTier(LocalTime.of(0,0), LocalTime.of(8,0), "VALLEY", 0.3),
                    new PriceTier(LocalTime.of(8,0), LocalTime.of(23,59), "PEAK", 1.2)),
            50.0, 10.0, 90.0, 100.0, 80.0
        );
        List<PlanPoint> points = PlanGenerator.generate(in);
        assertNotNull(points);
        assertFalse(points.isEmpty());
        // 充电窗口内应有 CHARGE 点
        boolean hasCharge = points.stream().anyMatch(p -> p.action().equals("CHARGE"));
        boolean hasDischarge = points.stream().anyMatch(p -> p.action().equals("DISCHARGE"));
        assertTrue(hasCharge && hasDischarge);
    }
}
