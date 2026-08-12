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

/** 需量管理接口（P1-2）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。 */
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

	/** 某日 96 槽位需量记录（升序）。 */
	@GetMapping("/records")
	public Result<List<EmsDemandRecord>> records(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result
			.ok(recordService.listByRange(requireTenant(), stationId, date.atStartOfDay(), date.atTime(23, 59, 59)));
	}

	/** 站点需量配置；未配置返回 null。 */
	@GetMapping("/config")
	public Result<EmsDemandConfig> config(@RequestParam Long stationId) {
		return Result.ok(configService.getByStation(stationId));
	}

	/** 站点需量配置 upsert（限值/费率）。 */
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

	/** 需量节省估算（复用收益服务，避免两套口径）。 */
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
