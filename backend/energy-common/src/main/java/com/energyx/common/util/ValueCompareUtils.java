package com.energyx.common.util;

import java.util.Locale;

/**
 * 通用数值/字符串比较工具（规则引擎与告警引擎共用的比较语义，Phase 11 下沉）。
 *
 * <p>
 * 比较语义：GT/GTE/LT/LTE 仅接受数值（字符串可解析为数值时也参与比较，解析失败视为不满足）； EQ/NEQ 两端均可解析为数值时按数值比较，否则按字符串比较。
 * </p>
 */
public final class ValueCompareUtils {

	private ValueCompareUtils() {
	}

	/** 通用比较（返回 false 表示不满足/无法比较） */
	public static boolean compare(String op, Object current, Object threshold) {
		if (op == null || current == null || threshold == null) {
			return false;
		}
		switch (op.toUpperCase(Locale.ROOT)) {
			case "GT":
				return cmp(current, threshold) > 0;
			case "GTE":
				return cmp(current, threshold) >= 0;
			case "LT":
				return cmp(current, threshold) < 0;
			case "LTE":
				return cmp(current, threshold) <= 0;
			case "EQ":
				return equalsValue(current, threshold);
			case "NEQ":
				return !equalsValue(current, threshold);
			default:
				return false;
		}
	}

	/** 数值比较；任一侧不可解析为数值时返回 NaN（所有比较结果为 false） */
	private static double cmp(Object a, Object b) {
		Double da = toDouble(a);
		Double db = toDouble(b);
		if (da == null || db == null) {
			return Double.NaN;
		}
		return Double.compare(da, db);
	}

	/** EQ：两侧均可解析为数值 → 数值相等；否则 → 字符串相等 */
	private static boolean equalsValue(Object a, Object b) {
		Double da = toDouble(a);
		Double db = toDouble(b);
		if (da != null && db != null) {
			return Double.compare(da, db) == 0;
		}
		return String.valueOf(a).equals(String.valueOf(b));
	}

	private static Double toDouble(Object v) {
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v instanceof String s) {
			try {
				return Double.parseDouble(s.trim());
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

}
