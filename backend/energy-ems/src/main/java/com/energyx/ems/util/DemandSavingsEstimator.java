package com.energyx.ems.util;

import com.energyx.ems.entity.EmsDemandRecord;

import java.math.BigDecimal;
import java.util.List;

/**
 * 需量电费节省估算（P1-2，纯函数）。
 *
 * <p>
 * 口径：未削峰最大需量 = max(各槽位 demand_kw + shaved_kw)（削峰放掉的功率加回，估算无电池场景）； 实际最大需量 = max(各槽位
 * demand_kw)；节省金额 = (未削峰 − 实际) × 费率 × 期数系数。
 * </p>
 */
public final class DemandSavingsEstimator {

	private DemandSavingsEstimator() {
	}

	/** 周期内实际最大需量（kW）。无记录 → 0。 */
	public static double actualMax(List<EmsDemandRecord> records) {
		return records.stream().mapToDouble(r -> num(r.getDemandKw())).max().orElse(0);
	}

	/** 未削峰最大需量（kW）：把削峰时放掉的功率加回。无记录 → 0。 */
	public static double unshavedMax(List<EmsDemandRecord> records) {
		return records.stream().mapToDouble(r -> num(r.getDemandKw()) + num(r.getShavedKw())).max().orElse(0);
	}

	/** 节省金额（元）= (未削峰 − 实际) × 费率 × 期数系数。无记录 / 费率 ≤ 0 → 0，恒非负。 */
	public static double estimate(List<EmsDemandRecord> records, double rate, double periodFactor) {
		if (records.isEmpty() || rate <= 0) {
			return 0;
		}
		return Math.max(0, unshavedMax(records) - actualMax(records)) * rate * periodFactor;
	}

	private static double num(BigDecimal v) {
		return v == null ? 0 : v.doubleValue();
	}

}
