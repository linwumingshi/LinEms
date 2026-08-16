package com.energyx.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.service.EmsStrategyService;
import com.energyx.ems.web.dto.EmsStrategySaveReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 策略管理接口。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>POST /ems/strategy — 创建策略</li>
 * <li>GET /ems/strategy/page — 分页查询策略</li>
 * <li>PUT /ems/strategy/{strategyId} — 更新策略</li>
 * <li>DELETE /ems/strategy/{strategyId} — 删除策略</li>
 * <li>PUT /ems/strategy/{strategyId}/status — 切换策略状态</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/strategy")
public class EmsStrategyController {

	private final EmsStrategyService service;

	public EmsStrategyController(EmsStrategyService service) {
		this.service = service;
	}

	/**
	 * 创建策略。将请求体转换为 {@link EmsStrategy} 实体后持久化。
	 * @param req 请求体，字段说明见 {@link EmsStrategySaveReq}
	 * @return {@link Result}<{@link EmsStrategy}> 创建后的策略（含自增主键）
	 */
	@PostMapping
	public Result<EmsStrategy> create(@Valid @RequestBody EmsStrategySaveReq req) {
		return Result.ok(service.create(req.toEntity()));
	}

	/**
	 * 分页查询策略。支持按站点、类型、状态筛选。
	 * @param pageNo 页码（来源：查询参数，默认 1）
	 * @param pageSize 每页条数（来源：查询参数，默认 10）
	 * @param stationId 站点 ID（来源：查询参数，可选）
	 * @param type 策略类型（来源：查询参数，可选）
	 * @param status 策略状态（来源：查询参数，可选）
	 * @return {@link Result}<{@link PageResult}<{@link EmsStrategy}>> 分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<EmsStrategy>> page(@RequestParam(defaultValue = "1") long pageNo,
			@RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) Long stationId,
			@RequestParam(required = false) String type, @RequestParam(required = false) Integer status) {
		return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, type, status)));
	}

	/**
	 * 更新策略。将路径中的 strategyId 写入请求体后转换为实体并持久化。
	 * @param strategyId 策略 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link EmsStrategySaveReq}
	 * @return {@link Result}<{@link EmsStrategy}> 更新后的策略
	 */
	@PutMapping("/{strategyId}")
	public Result<EmsStrategy> update(@PathVariable Long strategyId, @Valid @RequestBody EmsStrategySaveReq req) {
		req.setStrategyId(strategyId);
		return Result.ok(service.update(req.toEntity()));
	}

	/**
	 * 删除策略（逻辑删除）。
	 * @param strategyId 策略 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@DeleteMapping("/{strategyId}")
	public Result<Void> delete(@PathVariable Long strategyId) {
		service.delete(strategyId);
		return Result.ok();
	}

	/**
	 * 切换策略状态（启用/停用）。
	 * @param strategyId 策略 ID（来源：路径变量）
	 * @param status 目标状态；1=启用，0=停用（来源：查询参数）
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@PutMapping("/{strategyId}/status")
	public Result<Void> switchStatus(@PathVariable Long strategyId, @RequestParam int status) {
		service.switchStatus(strategyId, status);
		return Result.ok();
	}

}
