package com.energyx.ems.web.dto;

import com.energyx.common.enums.RevenuePeriodType;
import lombok.Data;

import java.math.BigDecimal;

/** 收益核算时段 summary（P1-1）。电量 kWh、金额 元；demandSavings 在 P1-2 前恒 0。 */
@Data
public class RevenueSummary {

	/** 站点 ID */
	private Long stationId;

	/** 收益统计周期，取值见 {@link RevenuePeriodType}（DAY/MONTH/YEAR） */
	private RevenuePeriodType periodType;

	/** 统计区间起始日期（yyyy-MM-dd） */
	private String startDate;

	/** 统计区间结束日期（yyyy-MM-dd） */
	private String endDate;

	/** 统计区间天数 */
	private int daysCount;

	/** 充电电量（kWh） */
	private double chargeEnergy;

	/** 放电电量（kWh） */
	private double dischargeEnergy;

	/** 总电量（kWh） */
	private double totalEnergy;

	/** 套利收益（元） */
	private double arbitrageRevenue;

	/** 需量节省收益（元）；P1-2 前恒为 0 */
	private double demandSavings;

	/** 总收益（元）= 套利收益 + 需量节省 */
	private double totalRevenue;

	/** 投资额（元）；未配置为 null */
	private BigDecimal investmentAmount;

	/** 回本周期（年）；投资额未配置或年化收益 ≤ 0 为 null */
	private Double paybackYears;

	/** 是否已配置投资额 */
	private boolean hasInvestment;

}
