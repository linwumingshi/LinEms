package com.energyx.ems.web.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class EmsPlanGenerateReq {

	/** 站点 ID */
	private Long stationId;

	/** 引用策略 ID */
	private Long strategyId;

	/** 计划日期 */
	private LocalDate planDate;

}
