package com.energyx.ems.web.dto;

import lombok.Data;

/** 收益趋势点（P1-1）。label：月视图 MM-dd、年视图 yyyy-MM。 */
@Data
public class RevenueTrendPoint {

	/** 横坐标标签；月视图为 MM-dd，年视图为 yyyy-MM */
	private String label;

	/** 充电电量（kWh） */
	private double chargeEnergy;

	/** 放电电量（kWh） */
	private double dischargeEnergy;

	/** 收益（元） */
	private double revenue;

}
