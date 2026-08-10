package com.energyx.ems.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.service.EmsPlanService;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.web.dto.EmsPlanGenerateReq;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/ems/plan")
public class EmsPlanController {

	private final EmsPlanService service;

	public EmsPlanController(EmsPlanService service) {
		this.service = service;
	}

	@PostMapping("/generate")
	public Result<EmsPlan> generate(@RequestBody EmsPlanGenerateReq req) {
		return Result.ok(service.generate(req.getStationId(), req.getStrategyId(), req.getPlanDate()));
	}

	@PostMapping("/{planId}/dispatch")
	public Result<Integer> dispatch(@PathVariable Long planId) {
		return Result.ok(service.dispatch(planId));
	}

	@GetMapping("/page")
	public Result<PageResult<EmsPlan>> page(@RequestParam(defaultValue = "1") long pageNo,
			@RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) Long stationId) {
		return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId)));
	}

	@GetMapping("/{planId}/points")
	public Result<List<PlanPoint>> points(@PathVariable Long planId) {
		return Result.ok(service.getPoints(planId));
	}

}
