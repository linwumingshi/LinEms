package com.energyx.ems.web.dto;

import com.energyx.common.enums.RevenuePeriodType;
import lombok.Data;

/** 需量节省估算视图（P1-2）。金额 元、功率 kW。 */
@Data
public class DemandSavingsView {

	/** 站点 ID */
	private Long stationId;

	/** 收益统计周期，取值见 {@link RevenuePeriodType}（DAY/MONTH/YEAR） */
	private RevenuePeriodType periodType;

	/** 统计区间起始日期（yyyy-MM-dd） */
	private String startDate;

	/** 统计区间结束日期（yyyy-MM-dd） */
	private String endDate;

	/** 实际最大需量（kW，经削峰后） */
	private double actualMaxKw;

	/** 未削峰最大需量（kW） */
	private double unshavedMaxKw;

	/** 节省金额（元） */
	private double savings;

}
