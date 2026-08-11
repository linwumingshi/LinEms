package com.energyx.ems.web.dto;

import lombok.Data;

/** 收益趋势点（P1-1）。label：月视图 MM-dd、年视图 yyyy-MM。 */
@Data
public class RevenueTrendPoint {

	private String label;

	private double chargeEnergy;

	private double dischargeEnergy;

	private double revenue;

}
