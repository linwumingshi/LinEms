package com.energyx.ems.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划生成器：把启用策略解析为 24h 充放电点序列（5 分钟粒度）。 纯函数：不碰 DB / MQTT，输入输出全在方法签名，便于单测。
 * 本期只实现峰谷套利（PEAK_VALLEY）；其余策略类型返回空列表，留给后续 AI 服务扩展。
 */
public final class PlanGenerator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 点序列粒度（分钟） */
	private static final int SLOT_MIN = 5;

	private PlanGenerator() {
	}

	/**
	 * 峰谷套利：默认手工窗口模式（谷段充电窗口出 CHARGE 点，峰段放电窗口出 DISCHARGE 点，窗口功率 = min(window.powerLimit,
	 * 包络上限)）；当 config.priceDriven=true 时改走分时电价驱动分支（谷充峰放）。 SOC 演进为近似 （YAGNI：精确 SOC
	 * 属模型层），但点内 socTarget 恒在 [socMin, socMax]。
	 * @param in 计划输入（策略类型 / config JSON / 电价 / 初始 SOC / 安全包络）
	 * @return 按时间升序的点序列
	 */
	public static List<PlanPoint> generate(PlanInput in) {
		if (!"PEAK_VALLEY".equals(in.strategyType())) {
			return List.of();
		}
		List<PlanPoint> points = new ArrayList<>();
		try {
			JsonNode cfg = MAPPER.readTree(in.config());
			double soc = in.socInit();
			if (cfg.path("priceDriven").asBoolean(false)) {
				soc = generatePriceDriven(in, cfg, soc, points);
			}
			else {
				soc = generateByWindows(in, cfg, soc, points);
			}
			// 当日尾点锚定待机（保证前端图时间轴完整）；SOC 收进包络，防近似演进一槽越界被安全包络校验拒绝
			soc = Math.min(in.socMax(), Math.max(in.socMin(), soc));
			points.add(new PlanPoint(LocalTime.of(23, 55), "STANDBY", 0, soc));
			// 按时间升序（不依赖 config 窗口书写顺序）
			points.sort(Comparator.comparing(PlanPoint::time));
		}
		catch (Exception e) {
			throw new IllegalArgumentException("策略配置解析失败: " + e.getMessage(), e);
		}
		return points;
	}

	/** 手工窗口模式（原逻辑原样提取）：逐窗口出点，返回演进后的 SOC。 */
	private static double generateByWindows(PlanInput in, JsonNode cfg, double soc, List<PlanPoint> points) {
		for (JsonNode w : cfg.path("chargeWindows")) {
			LocalTime start = LocalTime.parse(w.path("start").asText());
			LocalTime end = LocalTime.parse(w.path("end").asText());
			double power = windowPower(w, in.chargePowerMax());
			for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
				if (soc >= in.socMax())
					break;
				points.add(new PlanPoint(t, "CHARGE", power, soc));
				soc += power * SLOT_MIN / 60.0 * 0.01;
			}
		}
		for (JsonNode w : cfg.path("dischargeWindows")) {
			LocalTime start = LocalTime.parse(w.path("start").asText());
			LocalTime end = LocalTime.parse(w.path("end").asText());
			double power = windowPower(w, in.dischargePowerMax());
			for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
				if (soc <= in.socMin())
					break;
				points.add(new PlanPoint(t, "DISCHARGE", power, soc));
				soc -= power * SLOT_MIN / 60.0 * 0.01;
			}
		}
		return soc;
	}

	/**
	 * 电价驱动模式：按分时电价档位推导充放动作（DEEP/VALLEY→充，PEAK/PEEK→放，其余待机）。 功率 =
	 * config.chargePower/dischargePower（>0），否则回退包络上限。返回演进后的 SOC。
	 */
	private static double generatePriceDriven(PlanInput in, JsonNode cfg, double soc, List<PlanPoint> points) {
		List<PriceTier> tiers = in.prices();
		if (tiers == null || tiers.isEmpty()) {
			throw new IllegalArgumentException("未配置生效的分时电价");
		}
		double chargePower = cfg.path("chargePower").asDouble(0);
		if (chargePower <= 0)
			chargePower = in.chargePowerMax();
		double dischargePower = cfg.path("dischargePower").asDouble(0);
		if (dischargePower <= 0)
			dischargePower = in.dischargePowerMax();
		Set<LocalTime> seenStarts = new HashSet<>();
		for (PriceTier tier : tiers) {
			if (!seenStarts.add(tier.start()))
				continue; // 同 start 去重，保留首条（batchSave 非幂等残留防御）
			String action = switch (tier.priceType()) {
				case "DEEP", "VALLEY" -> "CHARGE";
				case "PEAK", "PEEK" -> "DISCHARGE";
				default -> null; // FLAT/其他 → 待机，不产点
			};
			if (action == null)
				continue;
			double power = "CHARGE".equals(action) ? chargePower : dischargePower;
			for (LocalTime t = tier.start(); t.isBefore(tier.end()); t = t.plusMinutes(SLOT_MIN)) {
				if ("CHARGE".equals(action)) {
					if (soc >= in.socMax())
						break;
					points.add(new PlanPoint(t, action, power, soc));
					soc += power * SLOT_MIN / 60.0 * 0.01;
				}
				else {
					if (soc <= in.socMin())
						break;
					points.add(new PlanPoint(t, action, power, soc));
					soc -= power * SLOT_MIN / 60.0 * 0.01;
				}
			}
		}
		return soc;
	}

	/** 窗口功率 = min(window.powerLimit, 包络功率上限)。 */
	private static double windowPower(JsonNode w, double envelopeMax) {
		return Math.min(w.path("powerLimit").asDouble(0), envelopeMax);
	}

}
