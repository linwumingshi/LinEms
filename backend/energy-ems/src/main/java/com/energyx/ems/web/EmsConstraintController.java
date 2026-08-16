package com.energyx.ems.web;

import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.service.EmsConstraintService;
import org.springframework.web.bind.annotation.*;

/**
 * 安全约束接口。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>GET /ems/constraint — 按站点查询安全约束</li>
 * <li>PUT /ems/constraint — 保存（upsert）安全约束</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/constraint")
public class EmsConstraintController {

	private final EmsConstraintService service;

	public EmsConstraintController(EmsConstraintService service) {
		this.service = service;
	}

	/**
	 * 按站点查询安全约束（下发前安全包络校验依据）。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @return {@link Result}<{@link EmsConstraint}> 安全约束；未配置为 null
	 */
	@GetMapping
	public Result<EmsConstraint> getByStation(@RequestParam Long stationId) {
		return Result.ok(service.getByStation(stationId));
	}

	/**
	 * 保存（upsert）安全约束。一站点一条，存在则更新。
	 * @param constraint 安全约束实体，字段说明见 {@link EmsConstraint}
	 * @return {@link Result}<{@link EmsConstraint}> 保存后的安全约束
	 */
	@PutMapping
	public Result<EmsConstraint> save(@RequestBody EmsConstraint constraint) {
		return Result.ok(service.saveConstraint(constraint));
	}

}
