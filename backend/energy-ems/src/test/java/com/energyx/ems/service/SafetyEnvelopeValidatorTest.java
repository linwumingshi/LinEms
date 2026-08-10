package com.energyx.ems.service;

import com.energyx.ems.util.PlanPoint;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SafetyEnvelopeValidatorTest {

	@Test
	void rejectsSocOutOfRange() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 100, 95.0)); // soc
																					// 95
																					// >
																					// max
																					// 90
		var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("SOC")));
	}

	@Test
	void rejectsPowerOverLimit() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 150, 50.0)); // 150
																					// >
																					// charge
																					// 100
		var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
		assertFalse(r.valid());
		assertTrue(r.rejections().stream().anyMatch(s -> s.contains("功率")));
	}

	@Test
	void passesWithinEnvelope() {
		var pts = List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 80, 50.0));
		var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
		assertTrue(r.valid());
	}

}
