package com.energyx.ems.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.enums.RevenuePeriodType;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.EmsRevenueService;
import com.energyx.ems.web.dto.DemandConfigReq;
import com.energyx.ems.web.dto.DemandSavingsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 需量管理接口（P1-2）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>GET /ems/demand/records — 查询某日需量检测槽位记录</li>
 * <li>GET /ems/demand/config — 查询站点需量配置</li>
 * <li>PUT /ems/demand/config — 保存（upsert）站点需量配置</li>
 * <li>GET /ems/demand/savings — 需量节省估算</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/demand")
public class DemandController {

	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	private final EmsRevenueService revenueService;

	public DemandController(EmsDemandConfigService configService, EmsDemandRecordService recordService,
			EmsRevenueService revenueService) {
		this.configService = configService;
		this.recordService = recordService;
		this.revenueService = revenueService;
	}

	/**
	 * 查询某日需量检测槽位记录（按槽位升序）。覆盖当日 00:00:00 ~ 23:59:59 区间。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @param date 统计日期（来源：查询参数）
	 * @return {@link Result}<{@link List}<{@link EmsDemandRecord}>> 需量槽位记录列表
	 * @throws com.energyx.common.exception.BusinessException 缺少租户上下文（UNAUTHORIZED）时抛出
	 */
	@GetMapping("/records")
	public Result<List<EmsDemandRecord>> records(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result
			.ok(recordService.listByRange(requireTenant(), stationId, date.atStartOfDay(), date.atTime(23, 59, 59)));
	}

	/**
	 * 查询站点需量配置。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @return {@link Result}<{@link EmsDemandConfig}> 站点需量配置；未配置为 null
	 */
	@GetMapping("/config")
	public Result<EmsDemandConfig> config(@RequestParam Long stationId) {
		return Result.ok(configService.getByStation(stationId));
	}

	/**
	 * 保存（upsert）站点需量配置（限值/费率）。stationId 必填；需量限值若存在须大于 0。
	 * @param req 请求体，字段说明见 {@link DemandConfigReq}
	 * @return {@link Result}<{@link EmsDemandConfig}> 保存后的站点需量配置
	 * @throws com.energyx.common.exception.BusinessException stationId
	 * 为空（PARAM_MISSING）或需量限值非正（PARAM_INVALID）时抛出
	 */
	@PutMapping("/config")
	public Result<EmsDemandConfig> saveConfig(@RequestBody DemandConfigReq req) {
		if (req.getStationId() == null) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "stationId 必填");
		}
		if (req.getDemandLimitKw() != null && req.getDemandLimitKw().signum() <= 0) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "需量限值必须大于 0");
		}
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(req.getStationId());
		cfg.setDemandLimitKw(req.getDemandLimitKw());
		cfg.setDemandRate(req.getDemandRate());
		return Result.ok(configService.upsert(cfg));
	}

	/**
	 * 需量节省估算（复用收益服务，避免两套口径）。
	 * @param stationId 站点 ID（来源：查询参数）
	 * @param periodType 统计周期，取值见 {@link RevenuePeriodType}（来源：查询参数）
	 * @param date 统计基准日期（来源：查询参数）
	 * @return {@link Result}<{@link DemandSavingsView}> 需量节省估算视图
	 */
	@GetMapping("/savings")
	public Result<DemandSavingsView> savings(@RequestParam Long stationId, @RequestParam RevenuePeriodType periodType,
			@RequestParam LocalDate date) {
		return Result.ok(revenueService.demandSavings(stationId, periodType, date));
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
