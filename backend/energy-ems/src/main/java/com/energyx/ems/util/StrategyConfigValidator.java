package com.energyx.ems.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 策略 config JSON 保存期校验（与前端 strategyConfig.ts 规则对齐，另加安全包络功率上限）。 仅 PEAK_VALLEY 有结构化
 * schema（窗口 start<end、功率>0）；其余策略类型仅要求合法 JSON 对象（生成器尚未实现，保存期兜底）。 包络上限传 null
 * 时跳过功率上限检查（该站尚未配置安全约束）。
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
		if (!"PEAK_VALLEY".equals(strategyType)) {
			return issues;
		}
		if (obj.path("priceDriven").asBoolean(false)) {
			// 电价驱动：窗口可空（生成器不读），仅校验功率上限
			checkPower(issues, "充电功率", obj.get("chargePower"), chargePowerMax);
			checkPower(issues, "放电功率", obj.get("dischargePower"), dischargePowerMax);
			return issues;
		}
		JsonNode charge = obj.path("chargeWindows");
		JsonNode discharge = obj.path("dischargeWindows");
		if (!charge.isArray() || !discharge.isArray()) {
			issues.add("缺少 chargeWindows 或 dischargeWindows 数组");
			return issues;
		}
		if (charge.isEmpty() && discharge.isEmpty()) {
			issues.add("请至少配置一个充电或放电窗口");
		}
		checkWindows(issues, "充电", charge, chargePowerMax);
		checkWindows(issues, "放电", discharge, dischargePowerMax);
		return issues;
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
