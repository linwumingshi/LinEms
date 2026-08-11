package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 需量检测纯函数（P1-2）：固定 15min 槽位定位、槽位均值、超限判定、削峰功率钳制。
 *
 * <p>
 * 槽位语义：00:00–00:15 … 共 96 槽/天（固定槽位，非滑动窗口），与工商业需量计费口径一致。 检测值取槽位内当前已积累样本均值
 * （槽位中途即可越限触发早期预警）。
 * </p>
 */
public final class DemandDetector {

	/** 槽位时长（分钟）。 */
	public static final int SLOT_MINUTES = 15;

	/** 单台 PCS 额定功率假设（kW）。ΣPCS 可用功率 = 活跃 PCS 数 × 该值（简化；SOC 深放保护由 socTarget 兜底）。 */
	public static final double PCS_RATED_KW = 100.0;

	private DemandDetector() {
	}

	/** ts（epoch ms）→ 所属 15min 槽位起点。 */
	public static LocalDateTime slotStart(long ts) {
		return slotStart(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
	}

	/** 时刻向下取整到 15min 槽位起点。 */
	public static LocalDateTime slotStart(LocalDateTime dt) {
		return dt.withSecond(0).withNano(0).minusMinutes(dt.getMinute() % SLOT_MINUTES);
	}

	/** 槽位终点（起点 + 15min）。 */
	public static LocalDateTime slotEnd(LocalDateTime start) {
		return start.plusMinutes(SLOT_MINUTES);
	}

	/** 槽位均值：rows 中落在 [start, start+15min) 且 power 非空样本的均值；无样本返回 0。 */
	public static double slotAvg(List<TsdbClient.TelemetryRow> rows, LocalDateTime start) {
		long startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		long endMs = slotEnd(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		double sum = 0;
		int n = 0;
		for (TsdbClient.TelemetryRow r : rows) {
			if (r.ts() >= startMs && r.ts() < endMs && r.power() != null) {
				sum += r.power();
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** 需量动作。 */
	public enum DemandAction {

		/** 未超限，不动作 */
		NONE,
		/** 超限且有活跃 PCS，下发削峰 */
		SHED,
		/** 超限但无活跃 PCS，只告警不削峰 */
		ALARM_ONLY

	}

	/** 检测结果。 */
	public record DetectResult(double demandKw, double limitKw, boolean overLimit, double shaveKw,
			DemandAction action) {
	}

	/**
	 * 超限判定 + 削峰功率计算。
	 *
	 * <p>
	 * 超限（均值 &gt; 限值）且活跃 PCS 数 &gt; 0 → 削峰功率 = min(超限量, 活跃 PCS 数 × PCS_RATED_KW)； 超限但无 PCS
	 * → ALARM_ONLY（削峰 0）；未超限或限值 ≤ 0 → NONE。
	 * </p>
	 */
	public static DetectResult detect(double slotAvgKw, double limitKw, int activePcsCount) {
		if (limitKw <= 0) {
			return new DetectResult(slotAvgKw, limitKw, false, 0, DemandAction.NONE);
		}
		boolean over = slotAvgKw > limitKw;
		if (!over) {
			return new DetectResult(slotAvgKw, limitKw, false, 0, DemandAction.NONE);
		}
		if (activePcsCount <= 0) {
			return new DetectResult(slotAvgKw, limitKw, true, 0, DemandAction.ALARM_ONLY);
		}
		double shave = Math.min(slotAvgKw - limitKw, activePcsCount * PCS_RATED_KW);
		return new DetectResult(slotAvgKw, limitKw, true, shave, DemandAction.SHED);
	}

}
