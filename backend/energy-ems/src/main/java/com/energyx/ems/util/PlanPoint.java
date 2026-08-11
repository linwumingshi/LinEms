package com.energyx.ems.util;

import java.time.LocalTime;

/**
 * 计划点：某个时刻的充放电指令（5 分钟粒度）。tempC 为可选温度遥测（℃），生成期无遥测为 null； 安全包络校验仅在 tempC 非空且配置了 temp_max
 * 时校验温度（P0-3）。4 参构造（tempC=null）保留旧调用面。
 */
public record PlanPoint(LocalTime time, String action, double powerKw, double socTarget, Double tempC) {

	public PlanPoint(LocalTime time, String action, double powerKw, double socTarget) {
		this(time, action, powerKw, socTarget, null);
	}
}
