package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 需量配置保存请求（P1-2）。 */
@Data
public class DemandConfigReq {

	private Long stationId;

	/** 需量限值 kW（>0 启用检测） */
	private BigDecimal demandLimitKw;

	/** 需量费率 ¥/kW·月 */
	private BigDecimal demandRate;

}
