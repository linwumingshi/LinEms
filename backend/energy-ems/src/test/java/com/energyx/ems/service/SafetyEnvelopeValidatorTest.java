package com.energyx.ems.service;

import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.util.PlanPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafetyEnvelopeValidatorTest {

	private static EmsConstraint constraint(BigDecimal socMin, BigDecimal socMax, BigDecimal chargeMax,
			BigDecimal dischargeMax) {
		EmsConstraint c = new EmsConstraint();
		c.setSocMin(socMin);
		c.setSocMax(socMax);
		c.setChargePowerMax(chargeMax);
		c.setDischargePowerMax(dischargeMax);
		return c;
	}

	@Test
	void rejectsSocOutOfRange() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 100, 95.0)); // soc
																					// 95
																					// >
																					// max
																					// 90
		var r = SafetyEnvelopeValidator.validate(pts,
				constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("80")));
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("SOC")));
	}

	@Test
	void rejectsPowerOverLimit() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 150, 50.0)); // 150
																					// >
																					// charge
																					// 100
		var r = SafetyEnvelopeValidator.validate(pts,
				constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("80")));
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("功率")));
	}

	@Test
	void passesWithinEnvelope() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 80, 50.0));
		var r = SafetyEnvelopeValidator.validate(pts,
				constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("80")));
		assertTrue(r.valid());
	}

	@Test
	void rejectsImpliedPowerCapFromVoltageCurrent() {
		// U=800V × I=200A → 160 kW 电气功率上限；放电 200kW 越限
		var pts = List.of(new PlanPoint(LocalTime.of(18, 0), "DISCHARGE", 200, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("300"));
		c.setVoltageMax(new BigDecimal("800"));
		c.setCurrentMax(new BigDecimal("200"));
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("电压/电流推导功率上限")));
	}

	@Test
	void skipsImpliedCapWhenOnlyOneOfVoltageCurrent() {
		// 只配电压不配电流 → 无法推导，不误伤
		var pts = List.of(new PlanPoint(LocalTime.of(18, 0), "DISCHARGE", 300, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("300"));
		c.setVoltageMax(new BigDecimal("800"));
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertTrue(r.valid());
	}

	@Test
	void envelopePowerCapKwApplied() {
		// safety_envelope.powerCapKw=120 → 充电 130 越限
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 130, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("200"),
				new BigDecimal("80"));
		c.setSafetyEnvelope("{\"powerCapKw\":120}");
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("功率上限")));
	}

	@Test
	void envelopeOverridesVoltageCurrent() {
		// 列未配电压/电流，envelope 提供 → 推导 cap=U×I/1000=120，放电 130 越限
		var pts = List.of(new PlanPoint(LocalTime.of(18, 0), "DISCHARGE", 130, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("300"));
		c.setSafetyEnvelope("{\"voltageMax\":800,\"currentMax\":150}");
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("电压/电流推导功率上限")));
	}

	@Test
	void rejectsTemperatureOverLimitWhenTempCProvided() {
		// temp_max=60，点带遥测温度 65 → 拒绝
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 80, 50.0, 65.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("80"));
		c.setTempMax(new BigDecimal("60"));
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("温度越界")));
	}

	@Test
	void passesWhenTempMaxSetButNoTempTelemetry() {
		// 生成期点无温度遥测（tempC=null）→ 温度校验不误伤
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 80, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("80"));
		c.setTempMax(new BigDecimal("60"));
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertTrue(r.valid());
	}

	@Test
	void malformedEnvelopeIgnored() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 80, 50.0));
		EmsConstraint c = constraint(new BigDecimal("10"), new BigDecimal("90"), new BigDecimal("100"),
				new BigDecimal("80"));
		c.setSafetyEnvelope("not-json"); // 脏 JSON 不阻断生成
		var r = SafetyEnvelopeValidator.validate(pts, c);
		assertTrue(r.valid());
	}

}
