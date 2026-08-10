package com.energyx.ems.web.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class EmsPlanGenerateReq {

	private Long stationId;

	private Long strategyId;

	private LocalDate planDate;

}
