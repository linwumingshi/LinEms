package com.energyx.ems.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.util.PlanPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SafetyEnvelopeValidator {

	/** safety_envelope JSON 解析结果；缺省 null。 */
	private record Envelope(Double voltageMax, Double currentMax, Double powerCapKw, Double tempMax) {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public record ValidationResult(boolean valid, List<String> rejections) {
	}

	/**
	 * 安全包络校验：SOC 越界 + 充电/放电功率越界 + 电压×电流推导功率上限（P0-3）+ 温度越界（tempC 有遥测才校验）。
	 * 电压/电流/温度上限均「非空才校验」：仅配置了对应列（或 safety_envelope JSON 覆写）才生效。
	 *
	 * <p>
	 * safety_envelope JSON 扩展（缺省 null，解析失败按未配置处理，不阻断生成）：
	 * {@code {"voltageMax":800,"currentMax":200,"powerCapKw":150,"tempMax":60}} ——
	 * voltageMax/currentMax/tempMax 覆写同名列，powerCapKw 为独立总功率上限。
	 * @param points 计划点序列
	 * @param c 安全约束（BigDecimal 列，null 列自动跳过对应校验）
	 */
	public static ValidationResult validate(List<PlanPoint> points, EmsConstraint c) {
		List<String> rejections = new ArrayList<>();
		double socMin = c.getSocMin().doubleValue();
		double socMax = c.getSocMax().doubleValue();
		double chargeMax = c.getChargePowerMax().doubleValue();
		double dischargeMax = c.getDischargePowerMax().doubleValue();
		Double tempMax = c.getTempMax() == null ? null : c.getTempMax().doubleValue();
		Double voltageMax = c.getVoltageMax() == null ? null : c.getVoltageMax().doubleValue();
		Double currentMax = c.getCurrentMax() == null ? null : c.getCurrentMax().doubleValue();
		Envelope env = parseEnvelope(c.getSafetyEnvelope());
		if (env != null) {
			if (env.voltageMax() != null)
				voltageMax = env.voltageMax();
			if (env.currentMax() != null)
				currentMax = env.currentMax();
			if (env.tempMax() != null)
				tempMax = env.tempMax();
		}
		// 电压×电流推导电气功率上限（直流近似 P=U·I/1000 kW）；非空才推导，两者缺一跳过
		Double capKw = voltageMax != null && currentMax != null ? voltageMax * currentMax / 1000.0 : null;
		if (env != null && env.powerCapKw() != null)
			capKw = capKw == null ? env.powerCapKw() : Math.min(capKw, env.powerCapKw());
		for (PlanPoint p : points) {
			if (p.socTarget() < socMin || p.socTarget() > socMax) {
				rejections.add(String.format("SOC 越界 time=%s soc=%.1f (允许 %.1f~%.1f)", p.time(), p.socTarget(), socMin,
						socMax));
			}
			if ("CHARGE".equals(p.action()) && p.powerKw() > chargeMax) {
				rejections.add(String.format("充电功率越界 time=%s power=%.1f (允许 %.1f)", p.time(), p.powerKw(), chargeMax));
			}
			if ("DISCHARGE".equals(p.action()) && p.powerKw() > dischargeMax) {
				rejections
					.add(String.format("放电功率越界 time=%s power=%.1f (允许 %.1f)", p.time(), p.powerKw(), dischargeMax));
			}
			if (capKw != null && !"STANDBY".equals(p.action()) && p.powerKw() > capKw) {
				rejections
					.add(String.format("电压/电流推导功率上限越界 time=%s power=%.1f (允许 %.1f)", p.time(), p.powerKw(), capKw));
			}
			if (tempMax != null && p.tempC() != null && p.tempC() > tempMax) {
				rejections.add(String.format("温度越界 time=%s temp=%.1f (允许 %.1f)", p.time(), p.tempC(), tempMax));
			}
		}
		return new ValidationResult(rejections.isEmpty(), rejections);
	}

	private static Envelope parseEnvelope(String json) {
		if (json == null || json.isBlank())
			return null;
		try {
			JsonNode n = MAPPER.readTree(json);
			return new Envelope(num(n, "voltageMax"), num(n, "currentMax"), num(n, "powerCapKw"), num(n, "tempMax"));
		}
		catch (Exception e) {
			return null; // 解析失败按未配置处理：不因脏 JSON 阻断正常计划生成
		}
	}

	private static Double num(JsonNode n, String key) {
		JsonNode v = n.path(key);
		return v.isNumber() ? v.doubleValue() : null;
	}

}
