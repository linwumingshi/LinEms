package com.energyx.rule.engine;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量 6 位 cron 表达式语法校验器（秒 分 时 日 月 周）。
 *
 * <p>
 * 只做语法级校验（字段个数、允许字符、数值范围），不做完整语义展开； 完整语义由 xxl-job 调度中心注册 job 时二次兜底。支持：* ? , - /
 * 数字以及周/月字母缩写 （Quartz 风格，兼容 xxl-job cron）。
 * </p>
 */
public final class CronValidator {

	private CronValidator() {
	}

	private static final Pattern FIELD = Pattern.compile("^[0-9*/?,\\-A-Z]+$");

	/** 各字段合法取值范围（秒/分/时/日/月/周） */
	private static final int[][] RANGES = { { 0, 59 }, { 0, 59 }, { 0, 23 }, { 1, 31 }, { 1, 12 }, { 0, 7 } };

	/** 周字段字母缩写（Quartz 风格） */
	private static final Map<String, Integer> DOW_NAMES = Map.ofEntries(Map.entry("SUN", 0), Map.entry("MON", 1),
			Map.entry("TUE", 2), Map.entry("WED", 3), Map.entry("THU", 4), Map.entry("FRI", 5), Map.entry("SAT", 6));

	/** 月字段字母缩写 */
	private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(Map.entry("JAN", 1), Map.entry("FEB", 2),
			Map.entry("MAR", 3), Map.entry("APR", 4), Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7),
			Map.entry("AUG", 8), Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

	/** 校验 6 位 cron（秒 分 时 日 月 周），非法返回 false */
	public static boolean isValid(String cron) {
		if (cron == null || cron.isBlank()) {
			return false;
		}
		String[] fields = cron.trim().split("\\s+");
		if (fields.length != 6) {
			return false;
		}
		for (int i = 0; i < fields.length; i++) {
			Map<String, Integer> names = i == 5 ? DOW_NAMES : (i == 4 ? MONTH_NAMES : null);
			if (!validField(fields[i], RANGES[i][0], RANGES[i][1], names)) {
				return false;
			}
		}
		return true;
	}

	/** 单个字段校验：* ? 合法；列表/区间/步进内的每个数值须在范围内（支持字母缩写） */
	private static boolean validField(String field, int min, int max, Map<String, Integer> nameMap) {
		if (!FIELD.matcher(field).matches()) {
			return false;
		}
		if ("*".equals(field) || "?".equals(field)) {
			return true;
		}
		for (String part : field.split(",")) {
			if (part.isEmpty()) {
				return false;
			}
			// 步进：a/b 或 */b
			if (part.contains("/")) {
				String[] step = part.split("/");
				if (step.length != 2 || !isInt(step[1])) {
					return false;
				}
				String base = step[0];
				if (!"*".equals(base) && !inRangeOrName(base, min, max, nameMap)) {
					return false;
				}
			}
			else if (part.contains("-")) {
				String[] range = part.split("-");
				if (range.length != 2 || !inRangeOrName(range[0], min, max, nameMap)
						|| !inRangeOrName(range[1], min, max, nameMap)) {
					return false;
				}
			}
			else if (!inRangeOrName(part, min, max, nameMap)) {
				return false;
			}
		}
		return true;
	}

	private static boolean inRangeOrName(String value, int min, int max, Map<String, Integer> nameMap) {
		if (isInt(value)) {
			int v = Integer.parseInt(value);
			return v >= min && v <= max;
		}
		if (nameMap != null) {
			Integer named = nameMap.get(value);
			return named != null && named >= min && named <= max;
		}
		return false;
	}

	private static boolean isInt(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

}
