package com.energyx.ems.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** StrategyConfigValidator：PEAK_VALLEY 结构化校验 + 功率≤包络。与前端 strategyConfig.ts 规则对齐。 */
class StrategyConfigValidatorTest {

	private static final BigDecimal CHARGE_MAX = new BigDecimal("100");

	private static final BigDecimal DISCHARGE_MAX = new BigDecimal("80");

	private static List<String> pv(String config) {
		return StrategyConfigValidator.validate(config, "PEAK_VALLEY", CHARGE_MAX, DISCHARGE_MAX);
	}

	@Test
	void invalidJsonRejected() {
		List<String> issues = pv("not json");
		assertFalse(issues.isEmpty());
		assertTrue(issues.get(0).contains("不是合法 JSON"));
	}

	@Test
	void nonObjectRejected() {
		List<String> issues = pv("[1,2]");
		assertEquals("配置必须是一个 JSON 对象", issues.get(0));
	}

	@Test
	void nonPeakValleyTypeOnlyRequiresJsonObject() {
		List<String> issues = StrategyConfigValidator.validate("{\"any\":true}", "DEMAND", CHARGE_MAX, DISCHARGE_MAX);
		assertTrue(issues.isEmpty());
	}

	@Test
	void manualValidWindowsPass() {
		String config = "{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}]}";
		assertTrue(pv(config).isEmpty());
	}

	@Test
	void missingWindowsRejected() {
		assertEquals("缺少 chargeWindows 或 dischargeWindows 数组", pv("{}").get(0));
	}

	@Test
	void bothWindowsEmptyRejected() {
		assertEquals("请至少配置一个充电或放电窗口", pv("{\"chargeWindows\":[],\"dischargeWindows\":[]}").get(0));
	}

	@Test
	void windowStartGteEndRejected() {
		String config = "{\"chargeWindows\":[{\"start\":\"06:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}]}";
		assertTrue(pv(config).get(0).contains("结束时间必须晚于开始时间"));
	}

	@Test
	void windowBadTimeFormatRejected() {
		String config = "{\"chargeWindows\":[{\"start\":\"6:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[]}";
		assertTrue(pv(config).get(0).contains("格式应为 HH:mm"));
	}

	@Test
	void windowZeroPowerRejected() {
		String config = "{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":0}],"
				+ "\"dischargeWindows\":[]}";
		assertTrue(pv(config).get(0).contains("功率上限必须大于 0"));
	}

	@Test
	void windowPowerExceedsEnvelopeRejected() {
		// 充电功率 150 > chargePowerMax 100
		String config = "{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":150}],"
				+ "\"dischargeWindows\":[]}";
		List<String> issues = pv(config);
		assertTrue(issues.stream().anyMatch(i -> i.contains("充电窗口 1 的功率上限超过安全包络上限 100")));
	}

	@Test
	void dischargeWindowPowerExceedsEnvelopeRejected() {
		String config = "{\"chargeWindows\":[],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":120}]}";
		List<String> issues = pv(config);
		assertTrue(issues.stream().anyMatch(i -> i.contains("放电窗口 1 的功率上限超过安全包络上限 80")));
	}

	@Test
	void priceDrivenValidPass() {
		assertTrue(pv("{\"priceDriven\":true,\"chargePower\":80,\"dischargePower\":60}").isEmpty());
	}

	@Test
	void priceDrivenWindowsOptional() {
		// 电价驱动：窗口数组缺省合法（生成器不读窗口，功率回退包络）
		assertTrue(pv("{\"priceDriven\":true}").isEmpty());
	}

	@Test
	void priceDrivenPowerExceedsEnvelopeRejected() {
		List<String> issues = pv("{\"priceDriven\":true,\"chargePower\":150}");
		assertTrue(issues.stream().anyMatch(i -> i.contains("充电功率超过安全包络上限 100")));
	}

	@Test
	void priceDrivenNonPositivePowerRejected() {
		List<String> issues = pv("{\"priceDriven\":true,\"dischargePower\":-5}");
		assertTrue(issues.stream().anyMatch(i -> i.contains("放电功率必须大于 0")));
	}

	@Test
	void envelopeNullSkipsPowerCaps() {
		// 未配置安全约束：跳过包络上限检查，仅结构校验
		String config = "{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":999}],"
				+ "\"dischargeWindows\":[]}";
		List<String> issues = StrategyConfigValidator.validate(config, "PEAK_VALLEY", null, null);
		assertTrue(issues.isEmpty());
	}

}
