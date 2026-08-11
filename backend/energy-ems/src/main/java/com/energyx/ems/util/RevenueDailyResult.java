package com.energyx.ems.util;

import java.time.LocalDate;
import java.util.List;

/** 收益核算单日聚合：逐槽明细 + 当日累计（P1-1）。 */
public record RevenueDailyResult(LocalDate date, double chargeEnergy, double dischargeEnergy, double revenue,
		List<RevenueSlot> slots) {
}
