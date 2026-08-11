package com.energyx.ems.web.dto;

import lombok.Data;

/** 需量节省估算视图（P1-2）。金额 元、功率 kW。 */
@Data
public class DemandSavingsView {

	private Long stationId;

	/** DAY/MONTH/YEAR */
	private String periodType;

	private String startDate;

	private String endDate;

	/** 实际最大需量 kW */
	private double actualMaxKw;

	/** 未削峰最大需量 kW */
	private double unshavedMaxKw;

	/** 节省金额 元 */
	private double savings;

}
