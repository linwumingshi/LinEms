package com.energyx.ems.util;

import java.time.LocalTime;

/** 收益核算单槽结果（单日明细行，P1-1）。time 为遥测采样时刻、action CHARGE/DISCHARGE、source 为方向来源。 */
public record RevenueSlot(LocalTime time, String action, double energyKwh, double price, double revenue,
		String source) {
}
