package com.energyx.ems.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsExecutionRecord;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.service.EmsPlanService;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.web.dto.EmsPlanGenerateReq;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 策略计划接口（生成 / 下发 / 查询）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>POST /ems/plan/generate — 生成策略计划</li>
 * <li>POST /ems/plan/{planId}/dispatch — 下发计划</li>
 * <li>GET /ems/plan/page — 分页查询计划</li>
 * <li>GET /ems/plan/{planId}/points — 查询计划充放电点序列</li>
 * <li>GET /ems/plan/{planId}/records — 查询计划执行记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/plan")
public class EmsPlanController {

	private final EmsPlanService service;

	public EmsPlanController(EmsPlanService service) {
		this.service = service;
	}

	/**
	 * 生成策略计划。依据站点、策略与计划日期生成充放电点序列并保存计划头。
	 * @param req 请求体，字段说明见 {@link EmsPlanGenerateReq}
	 * @return {@link Result}<{@link EmsPlan}> 生成的计划头
	 */
	@PostMapping("/generate")
	public Result<EmsPlan> generate(@RequestBody EmsPlanGenerateReq req) {
		return Result.ok(service.generate(req.getStationId(), req.getStrategyId(), req.getPlanDate()));
	}

	/**
	 * 下发计划。将计划下的各充放电点下发给设备，返回成功下发的点数。
	 * @param planId 计划 ID（来源：路径变量）
	 * @return {@link Result}<{@link Integer}> 成功下发的计划点数
	 */
	@PostMapping("/{planId}/dispatch")
	public Result<Integer> dispatch(@PathVariable Long planId) {
		return Result.ok(service.dispatch(planId));
	}

	/**
	 * 分页查询计划。支持按站点、状态筛选（状态为空时不过滤）。
	 * @param pageNo 页码（来源：查询参数，默认 1）
	 * @param pageSize 每页条数（来源：查询参数，默认 10）
	 * @param stationId 站点 ID（来源：查询参数，可选）
	 * @param status 计划状态（来源：查询参数，可选；为空则不过滤）
	 * @return {@link Result}<{@link PageResult}<{@link EmsPlan}>> 分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<EmsPlan>> page(@RequestParam(defaultValue = "1") long pageNo,
			@RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) Long stationId,
			@RequestParam(required = false) Integer status) {
		// status 可选：前端状态下拉为空时传 null，查询不过滤
		return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, status)));
	}

	/**
	 * 查询计划充放电点序列（5 分钟粒度）。
	 * @param planId 计划 ID（来源：路径变量）
	 * @return {@link Result}<{@link List}<{@link PlanPoint}>> 计划点列表
	 */
	@GetMapping("/{planId}/points")
	public Result<List<PlanPoint>> points(@PathVariable Long planId) {
		return Result.ok(service.getPoints(planId));
	}

	/**
	 * 计划执行记录查询（P0 执行闭环：前端展示各计划点下发/ACK 结果）。
	 * @param planId 计划 ID（来源：路径变量）
	 * @return {@link Result}<{@link List}<{@link EmsExecutionRecord}>> 执行记录列表
	 */
	@GetMapping("/{planId}/records")
	public Result<List<EmsExecutionRecord>> records(@PathVariable Long planId) {
		return Result.ok(service.records(planId));
	}

}
