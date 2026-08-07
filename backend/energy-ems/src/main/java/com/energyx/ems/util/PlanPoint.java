package com.energyx.ems.util;

import java.time.LocalTime;

/**
 * 计划点：某个时刻的充放电指令（5 分钟粒度）。
 */
public record PlanPoint(LocalTime time, String action, double powerKw, double socTarget) {
}
