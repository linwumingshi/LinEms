package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.common.enums.PriceType;
import com.energyx.common.enums.RevenuePeriodType;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.client.DeviceFeignClient;
import com.energyx.ems.model.DeviceInfo;
import com.energyx.ems.util.DemandSavingsEstimator;
import com.energyx.ems.util.PlanGenerator;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.util.PriceTier;
import com.energyx.ems.util.RevenueCalculator;
import com.energyx.ems.util.RevenueDailyResult;
import com.energyx.ems.util.RevenueSlot;
import com.energyx.ems.util.TdenginePlanWriter;
import com.energyx.ems.web.dto.DemandSavingsView;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueMetaReq;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import com.energyx.common.model.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收益核算编排（P1-1）：PCS 解析 → 逐日计划/电价查找表 → RevenueCalculator 聚合 → summary/trend/detail。
 * 租户敏感查询（plan/price/PCS 映射器）在主线完成；并行子线程只做 TSDB 拉取 + 纯函数计算（无租户依赖）。
 */
@Slf4j
@Service
public class EmsRevenueService {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

	/** PCS 产品标识（与 EmsPlanService.productKey 同一配置） */
	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String productKey;

	private final EmsPlanMapper planMapper;

	private final EmsElectricityPriceMapper priceMapper;

	private final EmsStationMetaService stationMetaService;

	private final DeviceFeignClient deviceFeignClient;

	private final TsdbClient tsdbClient;

	private final TdenginePlanWriter writer;

	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	public EmsRevenueService(EmsPlanMapper planMapper, EmsElectricityPriceMapper priceMapper,
			EmsStationMetaService stationMetaService, DeviceFeignClient deviceFeignClient, TsdbClient tsdbClient,
			TdenginePlanWriter writer, EmsDemandConfigService configService, EmsDemandRecordService recordService) {
		this.planMapper = planMapper;
		this.priceMapper = priceMapper;
		this.stationMetaService = stationMetaService;
		this.deviceFeignClient = deviceFeignClient;
		this.tsdbClient = tsdbClient;
		this.writer = writer;
		this.configService = configService;
		this.recordService = recordService;
	}

	/** 时段收益卡片。 */
	public RevenueSummary summary(Long stationId, RevenuePeriodType periodType, LocalDate date) {
		LocalDate[] range = resolveRange(periodType, date);
		Map<LocalDate, RevenueDailyResult> daily = dailyResults(stationId, range[0], range[1]);
		RevenueSummary s = new RevenueSummary();
		s.setStationId(stationId);
		s.setPeriodType(periodType);
		s.setStartDate(range[0].toString());
		s.setEndDate(range[1].toString());
		s.setDaysCount((int) ChronoUnit.DAYS.between(range[0], range[1]) + 1);
		double charge = 0;
		double discharge = 0;
		double revenue = 0;
		for (RevenueDailyResult r : daily.values()) {
			charge += r.chargeEnergy();
			discharge += r.dischargeEnergy();
			revenue += r.revenue();
		}
		s.setChargeEnergy(round2(charge));
		s.setDischargeEnergy(round2(discharge));
		s.setTotalEnergy(round2(charge + discharge));
		s.setArbitrageRevenue(round2(revenue));
		s.setDemandSavings(round2(demandSavings(stationId, periodType, date).getSavings()));
		s.setTotalRevenue(round2(revenue));
		fillPayback(s, range[0], range[1]);
		return s;
	}

	/** 趋势曲线：月视图按日、年视图按月。 */
	public List<RevenueTrendPoint> trend(Long stationId, RevenuePeriodType periodType, LocalDate date) {
		LocalDate[] range = resolveRange(periodType, date);
		Map<LocalDate, RevenueDailyResult> daily = dailyResults(stationId, range[0], range[1]);
		List<Map.Entry<LocalDate, RevenueDailyResult>> sorted = daily.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.toList();
		if (periodType == RevenuePeriodType.MONTH) {
			return sorted.stream().map(e -> point(e.getKey().format(DAY_LABEL), e.getValue())).toList();
		}
		// YEAR → 按月归并
		Map<YearMonth, double[]> monthly = new LinkedHashMap<>();
		for (Map.Entry<LocalDate, RevenueDailyResult> e : sorted) {
			YearMonth ym = YearMonth.from(e.getKey());
			double[] acc = monthly.computeIfAbsent(ym, k -> new double[3]);
			acc[0] += e.getValue().chargeEnergy();
			acc[1] += e.getValue().dischargeEnergy();
			acc[2] += e.getValue().revenue();
		}
		List<RevenueTrendPoint> out = new ArrayList<>();
		monthly.forEach((ym, acc) -> {
			RevenueTrendPoint p = new RevenueTrendPoint();
			p.setLabel(ym.toString());
			p.setChargeEnergy(round2(acc[0]));
			p.setDischargeEnergy(round2(acc[1]));
			p.setRevenue(round2(acc[2]));
			out.add(p);
		});
		return out;
	}

	/** 单日逐槽明细（按时刻升序）。 */
	public List<RevenueDetailRow> detail(Long stationId, LocalDate date) {
		RevenueDailyResult day = dailyResults(stationId, date, date).get(date);
		if (day == null) {
			return List.of();
		}
		List<RevenueDetailRow> rows = new ArrayList<>();
		for (RevenueSlot slot : day.slots()) {
			RevenueDetailRow r = new RevenueDetailRow();
			r.setTime(slot.time().toString());
			r.setAction(slot.action());
			r.setEnergyKwh(round2(slot.energyKwh()));
			r.setPrice(slot.price());
			r.setRevenue(round2(slot.revenue()));
			r.setSource(slot.source());
			rows.add(r);
		}
		rows.sort(Comparator.comparing(RevenueDetailRow::getTime));
		return rows;
	}

	/** 查电站投资元数据；未配置返回 null。 */
	public EmsStationMeta meta(Long stationId) {
		return stationMetaService.getByStation(stationId);
	}

	/** 需量节省估算（P1-2）：周期内槽位记录聚合 + 期数系数（月 ×1、年 ×12、日 ×1/30 示意）。无配置费率/无记录 → 0。 */
	public DemandSavingsView demandSavings(Long stationId, RevenuePeriodType periodType, LocalDate date) {
		Long tenant = requireTenant();
		LocalDate[] range = resolveRange(periodType, date);
		EmsDemandConfig cfg = configService.getByStation(stationId);
		double rate = cfg == null || cfg.getDemandRate() == null ? 0 : cfg.getDemandRate().doubleValue();
		double factor = switch (periodType) {
			case DAY -> 1.0 / 30.0; // 示意：按 30 天折算月基本电费
			case YEAR -> 12.0;
			default -> 1.0; // MONTH
		};
		List<EmsDemandRecord> recs = recordService.listByRange(tenant, stationId, range[0].atStartOfDay(),
				range[1].atTime(LocalTime.MAX));
		DemandSavingsView view = new DemandSavingsView();
		view.setStationId(stationId);
		view.setPeriodType(periodType);
		view.setStartDate(range[0].toString());
		view.setEndDate(range[1].toString());
		view.setActualMaxKw(round2(DemandSavingsEstimator.actualMax(recs)));
		view.setUnshavedMaxKw(round2(DemandSavingsEstimator.unshavedMax(recs)));
		view.setSavings(round2(DemandSavingsEstimator.estimate(recs, rate, factor)));
		return view;
	}

	/** 保存电站投资元数据（upsert）。 */
	public EmsStationMeta saveMeta(RevenueMetaReq req) {
		if (req.getStationId() == null) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "stationId 必填");
		}
		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(req.getStationId());
		meta.setInvestmentAmount(req.getInvestmentAmount());
		meta.setInstallDate(req.getInstallDate());
		return stationMetaService.upsert(meta);
	}

	/** 逐日聚合（跨设备合并）。租户敏感查询在此方法主线完成；设备并行只做 TSDB+纯计算。 */
	private Map<LocalDate, RevenueDailyResult> dailyResults(Long stationId, LocalDate start, LocalDate end) {
		Long tenant = requireTenant();
		Map<LocalDate, RevenueDailyResult> out = new LinkedHashMap<>();
		List<DeviceInfo> devices = resolvePcsDevices(tenant, stationId);
		if (devices == null || devices.isEmpty()) {
			return out;
		}
		List<EmsPlan> plans = planMapper
			.selectList(new LambdaQueryWrapper<EmsPlan>().eq(EmsPlan::getStationId, stationId)
				.between(EmsPlan::getPlanDate, start, end));
		Map<LocalDate, EmsPlan> planByDate = plans.stream().collect(Collectors.toMap(EmsPlan::getPlanDate, p -> p));
		List<EmsElectricityPrice> priceRows = priceMapper
			.selectList(new LambdaQueryWrapper<EmsElectricityPrice>().eq(EmsElectricityPrice::getTenantId, tenant)
				.eq(EmsElectricityPrice::getStationId, stationId)
				.eq(EmsElectricityPrice::getStatus, 1)
				.le(EmsElectricityPrice::getValidFrom, end)
				.ge(EmsElectricityPrice::getValidTo, start));
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			final LocalDate day = d; // 循环变量非 effectively final，lambda 捕获需本地副本
			EmsPlan plan = planByDate.get(d);
			Function<LocalTime, String> planAction = planActionLookup(plan);
			Function<LocalTime, Double> price = buildPriceLookup(priceLookupFor(priceRows, plan, d));
			List<RevenueDailyResult> dayResults = devices.parallelStream()
				.map(dev -> RevenueCalculator.aggregateDay(day,
						tsdbClient.history(dev.deviceId(), dev.productKey(), day), planAction, price))
				.filter(java.util.Objects::nonNull)
				.toList();
			double charge = 0;
			double discharge = 0;
			double revenue = 0;
			List<RevenueSlot> slots = new ArrayList<>();
			for (RevenueDailyResult r : dayResults) {
				charge += r.chargeEnergy();
				discharge += r.dischargeEnergy();
				revenue += r.revenue();
				slots.addAll(r.slots());
			}
			out.put(d, new RevenueDailyResult(d, charge, discharge, revenue, slots));
		}
		return out;
	}

	/** 当日电价档：电价驱动计划有 priceSnapshot → 快照；否则 → 电价表（status=1 且有效期覆盖该日）。 */
	private static List<PriceTier> priceLookupFor(List<EmsElectricityPrice> rows, EmsPlan plan, LocalDate date) {
		if (plan != null && isPriceDriven(plan.getPlanParam())) {
			List<PriceTier> snapshot = parseSnapshot(plan.getPlanParam());
			if (!snapshot.isEmpty()) {
				return snapshot;
			}
		}
		return rows.stream()
			.filter(p -> !p.getValidFrom().isAfter(date) && !p.getValidTo().isBefore(date))
			.map(p -> new PriceTier(p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue()))
			.toList();
	}

	/** 计划动作查找：计划点 → 5min 槽时刻→动作（遥测时刻向下取整匹配）。无可用动作返回 null。 */
	private Function<LocalTime, String> planActionLookup(EmsPlan plan) {
		if (plan == null) {
			return null;
		}
		try {
			List<PlanPoint> points = writer.read(plan.getStationId(), plan.getPlanDate());
			Map<LocalTime, String> bySlot = new HashMap<>();
			for (PlanPoint p : points) {
				if ("CHARGE".equals(p.action()) || "DISCHARGE".equals(p.action())) {
					bySlot.put(p.time(), p.action());
				}
			}
			if (bySlot.isEmpty()) {
				return null;
			}
			return t -> bySlot.get(floorToSlot(t));
		}
		catch (Exception e) {
			return null;
		}
	}

	/** 时刻向下取整到 5min 槽（计划点粒度）。 */
	private static LocalTime floorToSlot(LocalTime t) {
		return LocalTime.of(t.getHour(), t.getMinute() / PlanGenerator.SLOT_MIN * PlanGenerator.SLOT_MIN);
	}

	/** 档位列表 → 时刻→电价 查找函数（区间 [start, end) 匹配；未覆盖返回 null）。 */
	private static Function<LocalTime, Double> buildPriceLookup(List<PriceTier> tiers) {
		List<PriceTier> sorted = tiers.stream().sorted(Comparator.comparing(PriceTier::start)).toList();
		if (sorted.isEmpty()) {
			return null;
		}
		return t -> {
			for (PriceTier tier : sorted) {
				if (!t.isBefore(tier.start()) && t.isBefore(tier.end())) {
					return tier.price();
				}
			}
			return null;
		};
	}

	private static boolean isPriceDriven(String planParam) {
		try {
			return JSON.readTree(planParam).path("priceDriven").asBoolean(false);
		}
		catch (Exception e) {
			return false;
		}
	}

	/** 解析 plan_param.priceSnapshot（[{priceType,start,end,price}]）。 */
	private static List<PriceTier> parseSnapshot(String planParam) {
		try {
			JsonNode snap = JSON.readTree(planParam).path("priceSnapshot");
			List<PriceTier> out = new ArrayList<>();
			for (JsonNode tier : snap) {
				out.add(new PriceTier(LocalTime.parse(tier.path("start").asText()),
						LocalTime.parse(tier.path("end").asText()), PriceType.of(tier.path("priceType").asText()),
						tier.path("price").asDouble()));
			}
			return out;
		}
		catch (Exception e) {
			return List.of();
		}
	}

	/** ROI：投资额 ÷ 年化收益；年化按投运日期截断（投运前天数不计入回本核算）。 */
	private void fillPayback(RevenueSummary s, LocalDate start, LocalDate end) {
		EmsStationMeta meta = stationMetaService.getByStation(s.getStationId());
		boolean has = meta != null && meta.getInvestmentAmount() != null && meta.getInvestmentAmount().signum() > 0;
		s.setHasInvestment(has);
		if (!has) {
			return;
		}
		s.setInvestmentAmount(meta.getInvestmentAmount());
		LocalDate effectiveStart = meta.getInstallDate() != null && meta.getInstallDate().isAfter(start)
				? meta.getInstallDate() : start;
		long days = ChronoUnit.DAYS.between(effectiveStart, end) + 1;
		if (days > 0 && s.getTotalRevenue() > 0) {
			double annual = s.getTotalRevenue() * 365.0 / days;
			s.setPaybackYears(round2(meta.getInvestmentAmount().doubleValue() / annual));
		}
	}

	private static LocalDate[] resolveRange(RevenuePeriodType periodType, LocalDate date) {
		return switch (periodType) {
			case DAY -> new LocalDate[] { date, date };
			case MONTH -> new LocalDate[] { date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()) };
			case YEAR -> new LocalDate[] { date.withDayOfYear(1), date.withDayOfYear(date.lengthOfYear()) };
			default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "periodType 仅支持 DAY/MONTH/YEAR");
		};
	}

	private static RevenueTrendPoint point(String label, RevenueDailyResult r) {
		RevenueTrendPoint p = new RevenueTrendPoint();
		p.setLabel(label);
		p.setChargeEnergy(round2(r.chargeEnergy()));
		p.setDischargeEnergy(round2(r.dischargeEnergy()));
		p.setRevenue(round2(r.revenue()));
		return p;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

	/**
	 * 按租户 + 电站解析 PCS 设备（Feign 调 device 服务，deviceType=PCS）。
	 */
	private List<DeviceInfo> resolvePcsDevices(Long tenantId, Long stationId) {
		Result<List<DeviceInfo>> r = deviceFeignClient.listByStation(tenantId, stationId, productKey, "PCS");
		return r != null && r.isSuccess() ? r.getData() : List.of();
	}

}
