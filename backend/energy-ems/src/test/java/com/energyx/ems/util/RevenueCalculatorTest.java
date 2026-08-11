package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient.TelemetryRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** RevenueCalculator.aggregateDay 聚合语义：方向/积分/钳制/电价/收益符号。 */
class RevenueCalculatorTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private static final Function<LocalTime, String> NO_PLAN = null;

	private static final Function<LocalTime, Double> NO_PRICE = null;

	/** 构造一行遥测：从 00:00 起第 n 分钟。 */
	private static TelemetryRow row(int minute, double power, Integer runMode) {
		long ts = DAY.atTime(LocalTime.of(0, 0))
			.plusMinutes(minute)
			.atZone(java.time.ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli();
		return new TelemetryRow(ts, power, runMode);
	}

	@Test
	void runModeWinsOverPlanAction() {
		// runMode=1（充）但计划该刻为 DISCHARGE → 按充电计
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, 1), row(10, 60, 1)), plan,
				NO_PRICE);

		assertEquals(10.0, r.chargeEnergy(), 1e-9); // 60kW × 10/60h × 2 段
		assertEquals(0.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void fallsBackToPlanActionWhenNoRunMode() {
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, null), row(10, 60, null)), plan,
				NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void unknownDirectionSkipped() {
		// 无 runMode 且无计划 → 槽位不参与
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, null), row(10, 60, null)),
				NO_PLAN, NO_PRICE);

		assertEquals(0.0, r.chargeEnergy(), 1e-9);
		assertEquals(0.0, r.dischargeEnergy(), 1e-9);
		assertTrue(r.slots().isEmpty());
	}

	@Test
	void integratesEnergyOverInterval() {
		// 10 分钟间隔：60kW → 10 kWh
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, 2), row(10, 60, 2)), NO_PLAN,
				NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void clampsLongGap() {
		// 5 小时间隔 → dt 钳制 1h：能量 = 60×1 = 60，而非 300
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, 2), row(300, 60, 2)), NO_PLAN,
				NO_PRICE);

		assertEquals(60.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void zeroPowerSkipped() {
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 0, 1), row(10, 0, 1)), NO_PLAN,
				NO_PRICE);

		assertEquals(0.0, r.chargeEnergy(), 1e-9);
	}

	@Test
	void noPriceCountsEnergyButZeroRevenue() {
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, 2), row(10, 60, 2)), NO_PLAN,
				NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals(0.0, r.revenue(), 1e-9);
	}

	@Test
	void revenueSignChargeSubtractsDischargeAdds() {
		// 00:00/00:05 充 60kW（各 5min=5kWh）、00:10/00:15 放 60kW（各 5min）@0.3 充、@1.0 放 → 收益 =
		// 10×1.0 − 10×0.3 = 7
		Function<LocalTime, String> plan = t -> t.getMinute() >= 10 ? "DISCHARGE" : "CHARGE";
		Function<LocalTime, Double> price = t -> t.getMinute() >= 10 ? 1.0 : 0.3;
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, null), row(5, 60, null), row(10, 60, null), row(15, 60, null), row(20, 60, null)),
				plan, price);

		assertEquals(10.0, r.chargeEnergy(), 1e-9);
		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals(7.0, r.revenue(), 1e-9);
	}

	@Test
	void sourceMarkedRunModeOrPlan() {
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 1), row(10, 60, null), row(20, 60, null)), plan, NO_PRICE);

		assertEquals("RUN_MODE", r.slots().get(0).source());
		assertEquals("PLAN", r.slots().get(1).source());
	}

	@Test
	void standbyRunModeFallsBackToPlanAndSourcePlan() {
		// runMode==0（待机）非 1/2 → 方向回退计划 DISCHARGE；source 必须标 PLAN（非 RUN_MODE）
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY, List.of(row(0, 60, 0), row(10, 60, 0)), plan,
				NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals("PLAN", r.slots().get(0).source());
	}

}
