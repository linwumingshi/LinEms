package com.sanduo.energy.ems.util;

import java.time.LocalTime;

/**
 * 分时电价档位：起止时刻 + 电价类型（PEAK/VALLEY/FLAT）+ 电价。
 */
public record PriceTier(LocalTime start, LocalTime end, String priceType, double price) {
}
