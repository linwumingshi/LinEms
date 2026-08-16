package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 电站投资元数据保存请求（P1-1）。 */
@Data
public class RevenueMetaReq {

	/** 站点 ID */
	private Long stationId;

	/** 投资额（元） */
	private BigDecimal investmentAmount;

	/** 投运日期 */
	private LocalDate installDate;

}
