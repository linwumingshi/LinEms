package com.energyx.ems.web.dto;

import lombok.Data;

/** 单日逐槽明细（P1-1）。source：RUN_MODE/PLAN（方向来源）。 */
@Data
public class RevenueDetailRow {

	/** 时间槽标签（如 HH:mm） */
	private String time;

	/** 动作方向；CHARGE=充电，DISCHARGE=放电 */
	private String action;

	/** 该槽电量（kWh） */
	private double energyKwh;

	/** 该槽电价（元/kWh） */
	private double price;

	/** 该槽收益（元） */
	private double revenue;

	/** 来源；RUN_MODE=运行模式，PLAN=计划 */
	private String source;

}
