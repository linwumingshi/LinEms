package com.energyx.ems.util;

import com.energyx.common.enums.PriceType;

import java.time.LocalTime;

/**
 * 分时电价档位：起止时刻 + 电价类型（PEAK/VALLEY/FLAT/DEEP/PEEK）+ 电价。
 */
public record PriceTier(LocalTime start, LocalTime end, PriceType priceType, double price) {
}
