package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 需量配置保存请求（P1-2）。 */
@Data
public class DemandConfigReq {

	/**
	 * 站点 ID；必填
	 * @required
	 */
	private Long stationId;

	/** 需量限值（kW）；> 0 时启用检测，不传则仅配置费率 */
	private BigDecimal demandLimitKw;

	/** 需量费率（¥/kW·月） */
	private BigDecimal demandRate;

}
