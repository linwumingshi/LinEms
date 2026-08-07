package com.sanduo.energy.ems.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划生成器：把启用策略解析为 24h 充放电点序列（5 分钟粒度）。
 * 纯函数：不碰 DB / MQTT，输入输出全在方法签名，便于单测。
 * 本期只实现峰谷套利（PEAK_VALLEY）；其余策略类型返回空列表，留给后续 AI 服务扩展。
 */
public final class PlanGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 点序列粒度（分钟） */
    private static final int SLOT_MIN = 5;

    private PlanGenerator() {
    }

    /**
     * 峰谷套利：谷段充电窗口逐 5 分钟出 CHARGE 点，峰段放电窗口出 DISCHARGE 点；
     * 窗口功率 = min(window.powerLimit, 包络功率上限)。SOC 演进为近似
     * （YAGNI：精确 SOC 属模型层），但点内 socTarget 恒在 [socMin, socMax]。
     *
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
            for (JsonNode w : cfg.path("chargeWindows")) {
                LocalTime start = LocalTime.parse(w.path("start").asText());
                LocalTime end = LocalTime.parse(w.path("end").asText());
                double power = windowPower(w, in.chargePowerMax());
                for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
                    if (soc >= in.socMax()) break;
                    points.add(new PlanPoint(t, "CHARGE", power, soc));
                    soc += power * SLOT_MIN / 60.0 * 0.01; // 近似 SOC 演进
                }
            }
            for (JsonNode w : cfg.path("dischargeWindows")) {
                LocalTime start = LocalTime.parse(w.path("start").asText());
                LocalTime end = LocalTime.parse(w.path("end").asText());
                double power = windowPower(w, in.dischargePowerMax());
                for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
                    if (soc <= in.socMin()) break;
                    points.add(new PlanPoint(t, "DISCHARGE", power, soc));
                    soc -= power * SLOT_MIN / 60.0 * 0.01;
                }
            }
            // 当日尾点锚定待机（保证前端图时间轴完整）
            points.add(new PlanPoint(LocalTime.of(23, 55), "STANDBY", 0, soc));
        } catch (Exception e) {
            throw new IllegalArgumentException("策略配置解析失败: " + e.getMessage(), e);
        }
        return points;
    }

    /** 窗口功率 = min(window.powerLimit, 包络功率上限)。 */
    private static double windowPower(JsonNode w, double envelopeMax) {
        return Math.min(w.path("powerLimit").asDouble(0), envelopeMax);
    }
}
