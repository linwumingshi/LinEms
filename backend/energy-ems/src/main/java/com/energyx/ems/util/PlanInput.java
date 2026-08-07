package com.energyx.ems.util;

import java.util.List;

/**
 * 计划生成输入：策略类型 / 配置 JSON / 分时电价 / 初始 SOC / 安全包络。
 */
public record PlanInput(
    String strategyType,        // "PEAK_VALLEY"
    String config,              // JSON: {chargeWindows:[{start, end, powerLimit}], dischargeWindows:[...], socRange:{min,max}}
    List<PriceTier> prices,     // 分时电价
    double socInit,             // 初始 SOC %
    double socMin, double socMax, double chargePowerMax, double dischargePowerMax
) {
}
