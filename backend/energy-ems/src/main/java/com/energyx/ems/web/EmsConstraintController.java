package com.energyx.ems.web;

import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.service.EmsConstraintService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ems/constraint")
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
