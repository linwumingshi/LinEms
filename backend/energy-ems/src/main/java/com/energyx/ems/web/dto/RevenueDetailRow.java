package com.energyx.ems.web.dto;

import lombok.Data;

/** 单日逐槽明细（P1-1）。source：RUN_MODE/PLAN（方向来源）。 */
@Data
public class RevenueDetailRow {

	private String time;

	/** CHARGE/DISCHARGE */
	private String action;

	private double energyKwh;

	private double price;

	private double revenue;

	private String source;

}
