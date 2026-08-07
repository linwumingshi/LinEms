package com.sanduo.energy.ems.service;

import com.sanduo.energy.ems.util.PlanPoint;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class SafetyEnvelopeValidator {

    public record ValidationResult(boolean valid, List<String> rejections) {}

    /**
     * 安全包络校验：SOC 越界 + 充电/放电功率越界。温度校验（tempMax）本期推迟——
     * PlanPoint 无温度数据（温度是遥测值非计划值），接口保留占位，后续接温感遥测时启用。
     */
    public static ValidationResult validate(List<PlanPoint> points, double socMin, double socMax,
                                     double chargeMax, double dischargeMax, Double tempMax) {
        List<String> rejections = new ArrayList<>();
        for (PlanPoint p : points) {
            if (p.socTarget() < socMin || p.socTarget() > socMax) {
                rejections.add(String.format("SOC 越界 time=%s soc=%.1f (允许 %.1f~%.1f)",
                        p.time(), p.socTarget(), socMin, socMax));
            }
            if ("CHARGE".equals(p.action()) && p.powerKw() > chargeMax) {
                rejections.add(String.format("充电功率越界 time=%s power=%.1f (允许 %.1f)",
                        p.time(), p.powerKw(), chargeMax));
            }
            if ("DISCHARGE".equals(p.action()) && p.powerKw() > dischargeMax) {
                rejections.add(String.format("放电功率越界 time=%s power=%.1f (允许 %.1f)",
                        p.time(), p.powerKw(), dischargeMax));
            }
        }
        return new ValidationResult(rejections.isEmpty(), rejections);
    }
}
