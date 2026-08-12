package com.energyx.ems.web.dto;

import com.energyx.common.enums.RevenuePeriodType;
import lombok.Data;

import java.math.BigDecimal;

/** 收益核算时段 summary（P1-1）。电量 kWh、金额 元；demandSavings 在 P1-2 前恒 0。 */
@Data
public class RevenueSummary {

	private Long stationId;

	/** 收益统计周期（DAY/MONTH/YEAR） */
	private RevenuePeriodType periodType;

	private String startDate;

	private String endDate;

	private int daysCount;

	private double chargeEnergy;

	private double dischargeEnergy;

	private double totalEnergy;

	private double arbitrageRevenue;

	private double demandSavings;

	private double totalRevenue;

	/** 投资额 元；未配置为 null */
	private BigDecimal investmentAmount;

	/** 回本周期（年）；投资额未配置或年化收益 ≤0 为 null */
	private Double paybackYears;

	private boolean hasInvestment;

}
