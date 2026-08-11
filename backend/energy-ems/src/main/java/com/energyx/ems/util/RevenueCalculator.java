package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient.TelemetryRow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 收益聚合纯函数（P1-1）：按实际遥测积分电量、runMode 定方向（回退计划动作）、电价匹配，累计套利收益。 不碰 DB/Feign，输入输出全在签名，便于单测。
 */
public final class RevenueCalculator {

	/** 相邻采样点最大间隔（小时）：缺报长间隔不按满功率计，防数据空洞虚增电量 */
	public static final double MAX_SLOT_HOURS = 1.0;

	private RevenueCalculator() {
	}

	/**
	 * 单日聚合：对每行非零遥测，energy = |power| × dt（dt 钳制），方向 runMode 优先、计划动作回退， 电价匹配时段档（无电价计电量、收益记
	 * 0）。收益 = Σ(放电电量×价) − Σ(充电电量×价)。
	 * @param date 核算日期
	 * @param rows 遥测采样行（按 ts 升序；调用方保证）
	 * @param planAction 时刻→计划动作（CHARGE/DISCHARGE），无计划时刻返回 null；可为 null（全部靠 runMode）
	 * @param price 时刻→电价(元/kWh)，无电价返回 null；可为 null（全部收益记 0）
	 */
	public static RevenueDailyResult aggregateDay(LocalDate date, List<TelemetryRow> rows,
			Function<LocalTime, String> planAction, Function<LocalTime, Double> price) {
		double chargeEnergy = 0;
		double dischargeEnergy = 0;
		double revenue = 0;
		List<RevenueSlot> slots = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			TelemetryRow row = rows.get(i);
			if (row.power() == null || row.power() == 0) {
				continue;
			}
			double dt = slotHours(rows, i);
			if (dt <= 0) {
				continue; // 末采样点无后继间隔，不计
			}
			LocalTime time = localTimeOf(row.ts());
			String action = resolveAction(row, planAction, time);
			if (action == null) {
				continue; // 方向未知（无 runMode 且无计划动作），该槽不参与
			}
			double energy = Math.abs(row.power()) * dt;
			double unitPrice = 0;
			if (price != null) {
				Double p = price.apply(time);
				unitPrice = p == null ? 0 : p;
			}
			double slotRevenue = "CHARGE".equals(action) ? -energy * unitPrice : energy * unitPrice;
			chargeEnergy += "CHARGE".equals(action) ? energy : 0;
			dischargeEnergy += "DISCHARGE".equals(action) ? energy : 0;
			revenue += slotRevenue;
			slots.add(new RevenueSlot(time, action, energy, unitPrice, slotRevenue,
					row.runMode() != null ? "RUN_MODE" : "PLAN"));
		}
		return new RevenueDailyResult(date, chargeEnergy, dischargeEnergy, revenue, slots);
	}

	/** 左采样点覆盖间隔 = clamp((ts[i+1]-ts[i])/3600e3, 0, MAX_SLOT_HOURS)；末点返回 0。 */
	private static double slotHours(List<TelemetryRow> rows, int i) {
		if (i + 1 >= rows.size()) {
			return 0;
		}
		double hours = (rows.get(i + 1).ts() - rows.get(i).ts()) / 3_600_000.0;
		if (hours < 0) {
			hours = 0;
		}
		return Math.min(hours, MAX_SLOT_HOURS);
	}

	/** runMode(1充/2放) 优先；缺失回退计划动作；两者皆无返回 null。 */
	private static String resolveAction(TelemetryRow row, Function<LocalTime, String> planAction, LocalTime time) {
		if (row.runMode() != null) {
			if (row.runMode() == 1) {
				return "CHARGE";
			}
			if (row.runMode() == 2) {
				return "DISCHARGE";
			}
		}
		if (planAction != null) {
			String action = planAction.apply(time);
			if (action != null && ("CHARGE".equals(action) || "DISCHARGE".equals(action))) {
				return action;
			}
		}
		return null;
	}

	private static LocalTime localTimeOf(long tsMs) {
		return LocalTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneId.systemDefault());
	}

}
