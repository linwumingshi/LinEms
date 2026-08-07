package com.sanduo.energy.ems.web;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsPlan;
import com.sanduo.energy.ems.service.EmsPlanService;
import com.sanduo.energy.ems.util.PlanPoint;
import com.sanduo.energy.ems.web.dto.EmsPlanGenerateReq;
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
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) Long stationId) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId)));
    }

    @GetMapping("/{planId}/points")
    public Result<List<PlanPoint>> points(@PathVariable Long planId) {
        return Result.ok(service.getPoints(planId));
    }
}
