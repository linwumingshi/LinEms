package com.sanduo.energy.ems.web;

import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.service.EmsConstraintService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/constraint")
public class EmsConstraintController {

    private final EmsConstraintService service;

    public EmsConstraintController(EmsConstraintService service) {
        this.service = service;
    }

    @GetMapping
    public Result<EmsConstraint> getByStation(@RequestParam Long stationId) {
        return Result.ok(service.getByStation(stationId));
    }

    /** 保存安全约束（一电站一条 upsert）。 */
    @PutMapping
    public Result<EmsConstraint> save(@RequestBody EmsConstraint constraint) {
        return Result.ok(service.saveConstraint(constraint));
    }
}
