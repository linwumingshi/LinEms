package com.energyx.ems.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 策略 config JSON 保存期校验（与前端 strategyConfig.ts 规则对齐，另加安全包络功率上限）。 P0-4 落地后： PEAK_VALLEY /
 * DEMAND 校验窗口 schema（start<end、功率>0、≤包络），TIME 校验 schedule 时间段 schema； DR /
 * SOC_CTRL（不可生成）仅要求合法 JSON 对象。 包络上限传 null 时跳过功率上限检查（该站尚未配置安全约束）。
 */
public final class StrategyConfigValidator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** "HH:mm"（0-23 时、0-59 分，零填充）；字典序即时序序。 */
	private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

	private StrategyConfigValidator() {
	}

	/** 返回校验问题列表（空 = 通过）。 */
	public static List<String> validate(String config, String strategyType, BigDecimal chargePowerMax,
			BigDecimal dischargePowerMax) {
		List<String> issues = new ArrayList<>();
		JsonNode obj;
		try {
			obj = MAPPER.readTree(config);
		}
		catch (Exception e) {
			issues.add("配置不是合法 JSON：" + e.getMessage());
			return issues;
		}
		if (obj == null || !obj.isObject()) {
			issues.add("配置必须是一个 JSON 对象");
			return issues;
		}
		switch (strategyType) {
			case "PEAK_VALLEY" -> validatePeakValley(obj, issues, chargePowerMax, dischargePowerMax);
			case "DEMAND" -> validateDemand(obj, issues, chargePowerMax, dischargePowerMax);
			case "TIME" -> validateTime(obj, issues, chargePowerMax, dischargePowerMax);
			default -> {
				// DR（事件驱动）/SOC_CTRL（约束型）：生成期不可独立产点（P0-4 标注不可用），仅要求 JSON 对象
			}
		}
		return issues;
	}

	/** 峰谷套利：手工窗口模式（窗口 schema + 功率≤包络）或电价驱动模式（仅功率上限校验）。 */
	private static void validatePeakValley(JsonNode obj, List<String> issues, BigDecimal chargePowerMax,
			BigDecimal dischargePowerMax) {
		if (obj.path("priceDriven").asBoolean(false)) {
			// 电价驱动：窗口可空（生成器不读），仅校验功率上限
			checkPower(issues, "充电功率", obj.get("chargePower"), chargePowerMax);
			checkPower(issues, "放电功率", obj.get("dischargePower"), dischargePowerMax);
			return;
		}
		JsonNode charge = obj.path("chargeWindows");
		JsonNode discharge = obj.path("dischargeWindows");
		if (!charge.isArray() || !discharge.isArray()) {
			issues.add("缺少 chargeWindows 或 dischargeWindows 数组");
			return;
		}
		if (charge.isEmpty() && discharge.isEmpty()) {
			issues.add("请至少配置一个充电或放电窗口");
		}
		checkWindows(issues, "充电", charge, chargePowerMax);
		checkWindows(issues, "放电", discharge, dischargePowerMax);
	}

	/**
	 * 需量管理：谷段充电备能 + 需量时段放电削峰。 窗口形状同峰谷（不支持电价驱动，生成器忽略 priceDriven）； demandLimit 可选但须 >0（供
	 * P1-2 需量管理消费）。
	 */
	private static void validateDemand(JsonNode obj, List<String> issues, BigDecimal chargePowerMax,
			BigDecimal dischargePowerMax) {
		JsonNode charge = obj.path("chargeWindows");
		JsonNode discharge = obj.path("dischargeWindows");
		if (!charge.isArray() || !discharge.isArray()) {
			issues.add("缺少 chargeWindows 或 dischargeWindows 数组");
			return;
		}
		if (charge.isEmpty() && discharge.isEmpty()) {
			issues.add("请至少配置一个充电或放电窗口");
		}
		checkWindows(issues, "充电", charge, chargePowerMax);
		checkWindows(issues, "放电", discharge, dischargePowerMax);
		JsonNode limit = obj.get("demandLimit");
		if (limit != null && !limit.isMissingNode() && (!limit.isNumber() || limit.asDouble() <= 0)) {
			issues.add("需量限值 demandLimit 必须大于 0");
		}
	}

	/**
	 * 时间策略：schedule 时间段数组，每段 {start, end, action, power}。 action ∈
	 * CHARGE/DISCHARGE/STANDBY， 至少一个充/放时段；STANDBY 段不产点、无功率约束；充/放段功率必须 >0 且 ≤ 对应包络上限。
	 */
	private static void validateTime(JsonNode obj, List<String> issues, BigDecimal chargePowerMax,
			BigDecimal dischargePowerMax) {
		JsonNode schedule = obj.path("schedule");
		if (!schedule.isArray() || schedule.isEmpty()) {
			issues.add("缺少 schedule 时间段数组");
			return;
		}
		boolean actionable = false;
		for (int i = 0; i < schedule.size(); i++) {
			JsonNode s = schedule.get(i);
			int idx = i + 1;
			if (s == null || !s.isObject()) {
				issues.add("时段 " + idx + " 的开始/结束时间格式应为 HH:mm");
				continue;
			}
			String start = s.path("start").asText();
			String end = s.path("end").asText();
			if (!HHMM.matcher(start).matches() || !HHMM.matcher(end).matches()) {
				issues.add("时段 " + idx + " 的开始/结束时间格式应为 HH:mm");
				continue;
			}
			if (start.compareTo(end) >= 0) {
				issues.add("时段 " + idx + " 的结束时间必须晚于开始时间");
			}
			String action = s.path("action").asText();
			if (!"CHARGE".equals(action) && !"DISCHARGE".equals(action) && !"STANDBY".equals(action)) {
				issues.add("时段 " + idx + " 的 action 必须为 CHARGE/DISCHARGE/STANDBY");
				continue;
			}
			if ("STANDBY".equals(action)) {
				continue; // 待机时段不产点，无功率约束
			}
			actionable = true;
			JsonNode power = s.get("power");
			if (power == null || !power.isNumber() || power.asDouble() <= 0) {
				issues.add("时段 " + idx + " 的功率 power 必须大于 0");
			}
			else if ("CHARGE".equals(action) && chargePowerMax != null
					&& power.asDouble() > chargePowerMax.doubleValue()) {
				issues.add("时段 " + idx + " 的功率超过安全包络上限 " + chargePowerMax.toPlainString() + " kW");
			}
			else if ("DISCHARGE".equals(action) && dischargePowerMax != null
					&& power.asDouble() > dischargePowerMax.doubleValue()) {
				issues.add("时段 " + idx + " 的功率超过安全包络上限 " + dischargePowerMax.toPlainString() + " kW");
			}
		}
		if (!actionable) {
			issues.add("请至少配置一个充电或放电时段");
		}
	}

	private static void checkPower(List<String> issues, String label, JsonNode v, BigDecimal envelopeMax) {
		if (v == null || v.isMissingNode())
			return;
		if (!v.isNumber() || v.asDouble() <= 0) {
			issues.add(label + "必须大于 0");
			return;
		}
		if (envelopeMax != null && v.asDouble() > envelopeMax.doubleValue()) {
			issues.add(label + "超过安全包络上限 " + envelopeMax.toPlainString() + " kW");
		}
	}

	private static void checkWindows(List<String> issues, String label, JsonNode arr, BigDecimal envelopeMax) {
		for (int i = 0; i < arr.size(); i++) {
			JsonNode w = arr.get(i);
			int idx = i + 1;
			if (w == null || !w.isObject()) {
				issues.add("窗口 " + idx + " 的开始/结束时间格式应为 HH:mm");
				continue;
			}
			String start = w.path("start").asText();
			String end = w.path("end").asText();
			if (!HHMM.matcher(start).matches() || !HHMM.matcher(end).matches()) {
				issues.add("窗口 " + idx + " 的开始/结束时间格式应为 HH:mm");
				continue;
			}
			if (start.compareTo(end) >= 0) {
				issues.add("窗口 " + idx + " 的结束时间必须晚于开始时间");
			}
			JsonNode power = w.path("powerLimit");
			if (!power.isNumber() || power.asDouble() <= 0) {
				issues.add("窗口 " + idx + " 的功率上限必须大于 0");
			}
			else if (envelopeMax != null && power.asDouble() > envelopeMax.doubleValue()) {
				issues.add(label + "窗口 " + idx + " 的功率上限超过安全包络上限 " + envelopeMax.toPlainString() + " kW");
			}
		}
	}

}
