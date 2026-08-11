package com.energyx.ems.util;

import com.energyx.ems.entity.EmsDemandRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DemandSavingsEstimator：未削峰最大需量加回 shaved_kw；无记录/费率 0 → 0；期数系数生效。 */
class DemandSavingsEstimatorTest {

	private static EmsDemandRecord rec(double demandKw, double shavedKw) {
		EmsDemandRecord r = new EmsDemandRecord();
		r.setDemandKw(BigDecimal.valueOf(demandKw));
		r.setShavedKw(BigDecimal.valueOf(shavedKw));
		return r;
	}

	@Test
	void estimate_addsBackShavedKwForUnshavedMax() {
		// 峰值槽位：实际 500、削峰 200 → 未削峰 700；次高 650 → 未削峰 650
		List<EmsDemandRecord> recs = List.of(rec(500, 200), rec(650, 0), rec(400, 0));
		assertEquals(700.0, DemandSavingsEstimator.unshavedMax(recs));
		assertEquals(650.0, DemandSavingsEstimator.actualMax(recs));
		// (700 − 650) × 40 × 1 = 2000
		assertEquals(2000.0, DemandSavingsEstimator.estimate(recs, 40, 1));
	}

	@Test
	void estimate_noRecordsReturnsZero() {
		assertEquals(0.0, DemandSavingsEstimator.estimate(List.of(), 40, 1));
	}

	@Test
	void estimate_rateZeroReturnsZero() {
		assertEquals(0.0, DemandSavingsEstimator.estimate(List.of(rec(500, 200)), 0, 1));
	}

	@Test
	void estimate_appliesPeriodFactor() {
		List<EmsDemandRecord> recs = List.of(rec(500, 200), rec(650, 0));
		assertEquals(2000.0, DemandSavingsEstimator.estimate(recs, 40, 1)); // 月 ×1
		assertEquals(24000.0, DemandSavingsEstimator.estimate(recs, 40, 12)); // 年 ×12
	}

	@Test
	void estimate_neverNegative() {
		List<EmsDemandRecord> recs = List.of(rec(650, 0), rec(500, 0)); // 无削峰 → 差 0
		assertEquals(0.0, DemandSavingsEstimator.estimate(recs, 40, 1));
	}

}
