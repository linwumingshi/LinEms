package com.energyx.ems.web;

import com.energyx.common.model.Result;
import com.energyx.common.enums.RevenuePeriodType;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.service.EmsRevenueService;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueMetaReq;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 收益核算接口（P1-1）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。 */
@RestController
@RequestMapping("/ems/revenue")
public class EmsRevenueController {

	private final EmsRevenueService service;

	public EmsRevenueController(EmsRevenueService service) {
		this.service = service;
	}

	@GetMapping("/summary")
	public Result<RevenueSummary> summary(@RequestParam Long stationId, @RequestParam RevenuePeriodType periodType,
			@RequestParam LocalDate date) {
		return Result.ok(service.summary(stationId, periodType, date));
	}

	@GetMapping("/trend")
	public Result<List<RevenueTrendPoint>> trend(@RequestParam Long stationId,
			@RequestParam RevenuePeriodType periodType, @RequestParam LocalDate date) {
		return Result.ok(service.trend(stationId, periodType, date));
	}

	@GetMapping("/detail")
	public Result<List<RevenueDetailRow>> detail(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result.ok(service.detail(stationId, date));
	}

	@GetMapping("/meta")
	public Result<EmsStationMeta> meta(@RequestParam Long stationId) {
		return Result.ok(service.meta(stationId));
	}

	@PutMapping("/meta")
	public Result<EmsStationMeta> saveMeta(@RequestBody RevenueMetaReq req) {
		return Result.ok(service.saveMeta(req));
	}

}
