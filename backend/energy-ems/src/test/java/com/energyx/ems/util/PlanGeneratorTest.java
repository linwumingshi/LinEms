package com.energyx.ems.util;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanGeneratorTest {

	@Test
	void peakValley_basicChargeValleyDischargePeak() {
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}],
				 "dischargeWindows":[{"start":"18:00","end":"22:00","powerLimit":80}],
				 "socRange":{"min":10,"max":90}}
				""",
				List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(8, 0), "VALLEY", 0.3),
						new PriceTier(LocalTime.of(8, 0), LocalTime.of(23, 59), "PEAK", 1.2)),
				50.0, 10.0, 90.0, 100.0, 80.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		assertNotNull(points);
		assertFalse(points.isEmpty());
		// 充电窗口内应有 CHARGE 点
		boolean hasCharge = points.stream().anyMatch(p -> p.action().equals("CHARGE"));
		boolean hasDischarge = points.stream().anyMatch(p -> p.action().equals("DISCHARGE"));
		assertTrue(hasCharge && hasDischarge);
		// SOC 不越界 + 尾点锚定待机
		assertTrue(points.stream().allMatch(p -> p.socTarget() >= 10 && p.socTarget() <= 90));
		assertEquals(LocalTime.of(23, 55), points.get(points.size() - 1).time());
		assertEquals("STANDBY", points.get(points.size() - 1).action());
	}

	@Test
	void priceDriven_standardValleyChargePeakDischarge() {
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true,"chargePower":80,"dischargePower":60}
				""",
				List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(8, 0), "DEEP", 0.2),
						new PriceTier(LocalTime.of(8, 0), LocalTime.of(11, 0), "PEAK", 1.2),
						new PriceTier(LocalTime.of(11, 0), LocalTime.of(14, 0), "FLAT", 0.6),
						new PriceTier(LocalTime.of(14, 0), LocalTime.of(18, 0), "VALLEY", 0.3),
						new PriceTier(LocalTime.of(18, 0), LocalTime.of(22, 0), "PEEK", 1.5)),
				50.0, 10.0, 90.0, 100.0, 100.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		assertNotNull(points);
		// FLAT 段（11:00-14:00）无点
		assertTrue(points.stream()
			.noneMatch(p -> !p.time().isBefore(LocalTime.of(11, 0)) && p.time().isBefore(LocalTime.of(14, 0))));
		// 谷段充电功率 = config.chargePower（config 优先）
		PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
		assertEquals(80.0, charge.powerKw(), 1e-9);
		// 峰段放电功率 = config.dischargePower
		PlanPoint discharge = points.stream().filter(p -> p.action().equals("DISCHARGE")).findFirst().orElseThrow();
		assertEquals(60.0, discharge.powerKw(), 1e-9);
		// 尾点锚定
		assertEquals(LocalTime.of(23, 55), points.get(points.size() - 1).time());
		assertEquals("STANDBY", points.get(points.size() - 1).action());
	}

	@Test
	void priceDriven_powerFallsBackToEnvelopeWhenConfigMissing() {
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true}
				""",
				List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(4, 0), "VALLEY", 0.3),
						new PriceTier(LocalTime.of(12, 0), LocalTime.of(16, 0), "PEAK", 1.2)),
				50.0, 10.0, 90.0, 100.0, 80.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
		assertEquals(100.0, charge.powerKw(), 1e-9); // 回退 chargePowerMax
		PlanPoint discharge = points.stream().filter(p -> p.action().equals("DISCHARGE")).findFirst().orElseThrow();
		assertEquals(80.0, discharge.powerKw(), 1e-9); // 回退 dischargePowerMax
	}

	@Test
	void priceDriven_noPricesThrows() {
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true}
				""", List.of(), 50.0, 10.0, 90.0, 100.0, 80.0);
		assertThrows(IllegalArgumentException.class, () -> PlanGenerator.generate(in));
	}

	@Test
	void priceDriven_socReachesMaxThenChargeStops() {
		// socInit=89 贴近上限：100kW 每 5min 推进 0.0833% SOC，约 12 点到达 90 上限即停；若无 socMax break，4h
		// 满窗 48 点
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true,"chargePower":100}
				""", List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(4, 0), "VALLEY", 0.3)), 89.0, 10.0, 90.0,
				100.0, 80.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		List<PlanPoint> charges = points.stream().filter(p -> p.action().equals("CHARGE")).toList();
		// 充电点本身从不越界（break 在加点前判断）
		assertTrue(charges.stream().allMatch(p -> p.socTarget() <= 90.0001));
		assertTrue(charges.size() >= 12); // 至少推进到上限附近（12-13 个 5min 点 ≈ 1% SOC）
		assertTrue(charges.size() < 48); // 到达上限即停：远小于 4h 满窗 48 点
		assertTrue(charges.stream().allMatch(p -> p.powerKw() == 100.0));
		// 尾点 STANDBY 携带最终 SOC：近似演进允许一槽越界（d≈0.0833），钳制在 90 附近
		assertEquals(90.0, points.get(points.size() - 1).socTarget(), 0.1);
	}

	@Test
	void priceDriven_duplicateStartDedup() {
		// 同 start 双档（batchSave 非幂等残留）：保留首条 VALLEY(0-2)，跳过 DEEP(0-3)
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true}
				""",
				List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(2, 0), "VALLEY", 0.3),
						new PriceTier(LocalTime.of(0, 0), LocalTime.of(3, 0), "DEEP", 0.2)),
				50.0, 10.0, 90.0, 100.0, 80.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		long chargePoints = points.stream().filter(p -> p.action().equals("CHARGE")).count();
		assertTrue(chargePoints <= 24); // 2h/5min = 24 点上限
	}

	@Test
	void priceDriven_falseKeepsWindowBehavior() {
		// priceDriven=false + 手工窗口：走窗口逻辑，电价存在但不影响
		PlanInput in = new PlanInput("PEAK_VALLEY",
				"""
						{"priceDriven":false,"chargeWindows":[{"start":"02:00","end":"04:00","powerLimit":100}],"dischargeWindows":[{"start":"18:00","end":"20:00","powerLimit":80}]}
						""",
				List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(8, 0), "VALLEY", 0.3)), 50.0, 10.0, 90.0, 100.0,
				80.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
		assertEquals(LocalTime.of(2, 0), charge.time());
		assertEquals(100.0, charge.powerKw(), 1e-9); // 窗口逻辑：window.powerLimit
	}

	@Test
	void priceDriven_dischargeStopsAtSocMin() {
		// socInit=11 贴近下限：100kW 放电每 5min 推进 0.0833% SOC，约 12 点到达 10 下限即停；若无 socMin
		// break，10h 满窗 120 点
		PlanInput in = new PlanInput("PEAK_VALLEY", """
				{"priceDriven":true,"dischargePower":100}
				""", List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(10, 0), "PEAK", 1.2)), 11.0, 10.0, 90.0,
				100.0, 100.0);
		List<PlanPoint> points = PlanGenerator.generate(in);
		List<PlanPoint> discharges = points.stream().filter(p -> p.action().equals("DISCHARGE")).toList();
		// 放电点本身从不越界（break 在加点前判断）
		assertTrue(discharges.stream().allMatch(p -> p.socTarget() >= 9.9999));
		assertTrue(discharges.size() >= 12); // 至少推进到下限附近（12-13 个 5min 点 ≈ 1% SOC）
		assertTrue(discharges.size() < 120); // 到达下限即停：远小于 10h 满窗 120 点
		assertTrue(discharges.stream().allMatch(p -> p.powerKw() == 100.0));
		// 尾点 STANDBY 携带最终 SOC：近似演进允许一槽越界（d≈0.0833），钳制在 10 附近
		assertEquals(10.0, points.get(points.size() - 1).socTarget(), 0.1);
	}

}
