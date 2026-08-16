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

/**
 * 收益核算接口（P1-1）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>GET /ems/revenue/summary — 收益统计汇总</li>
 * <li>GET /ems/revenue/trend — 收益趋势曲线</li>
 * <li>GET /ems/revenue/detail — 单日逐槽收益明细</li>
 * <li>GET /ems/revenue/meta — 查询站点投资元数据</li>
 * <li>PUT /ems/revenue/meta — 保存站点投资元数据</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/revenue")
public class EmsRevenueController {

	private final EmsRevenueService service;

	public EmsRevenueController(EmsRevenueService service) {
		this.service = service;
	}

	/**
	 * 收益统计汇总。按站点与统计周期（日/月/年）汇总充放电电量与套利/需量收益。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @param periodType 统计周期，取值见 {@link RevenuePeriodType}（来源：查询参数）
	 * @param date 统计基准日期（来源：查询参数）
	 * @return {@link Result}<{@link RevenueSummary}> 收益汇总
	 */
	@GetMapping("/summary")
	public Result<RevenueSummary> summary(@RequestParam Long stationId, @RequestParam RevenuePeriodType periodType,
			@RequestParam LocalDate date) {
		return Result.ok(service.summary(stationId, periodType, date));
	}

	/**
	 * 收益趋势曲线。按统计周期返回逐点（月视图按日、年视图按月）充放电电量与收益。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @param periodType 统计周期，取值见 {@link RevenuePeriodType}（来源：查询参数）
	 * @param date 统计基准日期（来源：查询参数）
	 * @return {@link Result}<{@link List}<{@link RevenueTrendPoint}>> 收益趋势点列表
	 */
	@GetMapping("/trend")
	public Result<List<RevenueTrendPoint>> trend(@RequestParam Long stationId,
			@RequestParam RevenuePeriodType periodType, @RequestParam LocalDate date) {
		return Result.ok(service.trend(stationId, periodType, date));
	}

	/**
	 * 单日逐槽收益明细。返回指定日期每个时间槽的充放电方向与电量、电价、收益。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @param date 统计日期（来源：查询参数）
	 * @return {@link Result}<{@link List}<{@link RevenueDetailRow}>> 逐槽明细列表
	 */
	@GetMapping("/detail")
	public Result<List<RevenueDetailRow>> detail(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result.ok(service.detail(stationId, date));
	}

	/**
	 * 查询站点投资元数据（投资额、投运日期等）。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @return {@link Result}<{@link EmsStationMeta}> 站点投资元数据；未配置为 null
	 */
	@GetMapping("/meta")
	public Result<EmsStationMeta> meta(@RequestParam Long stationId) {
		return Result.ok(service.meta(stationId));
	}

	/**
	 * 保存（upsert）站点投资元数据。
	 * @param req 请求体，字段说明见 {@link RevenueMetaReq}
	 * @return {@link Result}<{@link EmsStationMeta}> 保存后的站点投资元数据
	 */
	@PutMapping("/meta")
	public Result<EmsStationMeta> saveMeta(@RequestBody RevenueMetaReq req) {
		return Result.ok(service.saveMeta(req));
	}

}
