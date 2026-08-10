package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsExecutionRecord;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsExecutionRecordMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import com.energyx.ems.util.PlanGenerator;
import com.energyx.ems.util.PlanInput;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.util.PriceTier;
import com.energyx.ems.util.TdenginePlanWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划生成编排：生成 → 安全包络校验 → TDengine 点序列 → 计划头落库 → 下发（复用 energy-command）。 租户取自策略行（@Scheduled
 * 线程无请求租户上下文，见 [[multi-tenant-isolation]]）； 约束/电价查询显式按该租户过滤，避免定时线程跨租户读到同 stationId 数据。
 */
@Slf4j
@Service
public class EmsPlanService {

	private final EmsStrategyMapper strategyMapper;

	private final EmsElectricityPriceMapper priceMapper;

	private final EmsConstraintMapper constraintMapper;

	private final EmsPlanMapper planMapper;

	private final EmsExecutionRecordMapper execMapper;

	private final SafetyEnvelopeValidator validator;

	private final TdenginePlanWriter writer;

	private final CommandClient commandClient;

	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String productKey;

	@Value("${energyx.ems.device-name:}")
	private String deviceName;

	/** 执行记录 params 列为 MySQL JSON，必须真实 JSON 序列化（不能 Map.toString）。 */
	private static final ObjectMapper JSON = new ObjectMapper();

	public EmsPlanService(EmsStrategyMapper strategyMapper, EmsElectricityPriceMapper priceMapper,
			EmsConstraintMapper constraintMapper, EmsPlanMapper planMapper, EmsExecutionRecordMapper execMapper,
			SafetyEnvelopeValidator validator, TdenginePlanWriter writer, CommandClient commandClient) {
		this.strategyMapper = strategyMapper;
		this.priceMapper = priceMapper;
		this.constraintMapper = constraintMapper;
		this.planMapper = planMapper;
		this.execMapper = execMapper;
		this.validator = validator;
		this.writer = writer;
		this.commandClient = commandClient;
	}

	/** 生成计划：查策略 → 电价 → 安全约束 → PlanGenerator 出点序列 → 包络校验 → 写 TDengine → 计划头落库。 */
	public EmsPlan generate(Long stationId, Long strategyId, LocalDate planDate) {
		EmsStrategy strategy = resolveStrategy(stationId, strategyId);
		if (strategy == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND,
					"未找到启用策略: stationId=" + stationId + (strategyId != null ? ", strategyId=" + strategyId : ""));
		}
		Long tenant = strategy.getTenantId();
		// 防重复生成：同电站同日期仅允许一个计划（配合 V2 唯一键 + writer 幂等写，杜绝重复下发）
		Long existing = planMapper.selectCount(new LambdaQueryWrapper<EmsPlan>().eq(EmsPlan::getStationId, stationId)
			.eq(EmsPlan::getPlanDate, planDate));
		if (existing != null && existing > 0) {
			throw new BusinessException(ErrorCode.CONFLICT,
					"该电站该日期已存在计划，请勿重复生成: stationId=" + stationId + ", planDate=" + planDate);
		}
		EmsConstraint constraint = constraintMapper
			.selectOne(new LambdaQueryWrapper<EmsConstraint>().eq(EmsConstraint::getTenantId, tenant)
				.eq(EmsConstraint::getStationId, stationId));
		if (constraint == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "未配置安全约束: stationId=" + stationId);
		}
		List<EmsElectricityPrice> prices = priceMapper
			.selectList(new LambdaQueryWrapper<EmsElectricityPrice>().eq(EmsElectricityPrice::getTenantId, tenant)
				.eq(EmsElectricityPrice::getStationId, stationId));
		List<PlanPoint> points = PlanGenerator.generate(toInput(strategy, constraint, prices));
		SafetyEnvelopeValidator.ValidationResult vr = validator.validate(points, constraint.getSocMin().doubleValue(),
				constraint.getSocMax().doubleValue(), constraint.getChargePowerMax().doubleValue(),
				constraint.getDischargePowerMax().doubleValue(),
				constraint.getTempMax() == null ? null : constraint.getTempMax().doubleValue());
		if (!vr.valid()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "安全包络校验未通过: " + String.join("; ", vr.rejections()));
		}
		try {
			writer.write(stationId, planDate, points);
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "TDengine 写入失败: " + e.getMessage());
		}
		EmsPlan plan = new EmsPlan();
		plan.setTenantId(strategy.getTenantId());
		plan.setStationId(stationId);
		plan.setStrategyId(strategy.getStrategyId());
		plan.setPlanDate(planDate);
		plan.setPlanType(3); // 混合
		plan.setStatus(0); // 待执行
		plan.setPlanParam(strategy.getConfig());
		planMapper.insert(plan);
		log.info("生成计划 planId={} stationId={} 点数={}", plan.getPlanId(), stationId, points.size());
		return plan;
	}

	/** 每日 00:05 为启用策略的电站生成次日计划（定时线程无租户上下文，遍历全量启用策略）。 */
	@Scheduled(cron = "0 5 0 * * *")
	public void generateDailyPlans() {
		LocalDate tomorrow = LocalDate.now().plusDays(1);
		List<EmsStrategy> enabled = strategyMapper
			.selectList(new LambdaQueryWrapper<EmsStrategy>().eq(EmsStrategy::getStatus, 1));
		Set<String> handled = new HashSet<>();
		for (EmsStrategy s : enabled) {
			String key = s.getTenantId() + ":" + s.getStationId();
			if (!handled.add(key)) {
				continue; // 同电站多策略只生成一次
			}
			try {
				generate(s.getStationId(), s.getStrategyId(), tomorrow);
			}
			catch (Exception e) {
				// 任一异常（业务失败/脏数据 NPE/DB 异常）都不能中止整日运行——单电站失败不影响其余
				log.warn("定时生成失败 stationId={} strategyId={}: {}", s.getStationId(), s.getStrategyId(), e.getMessage());
			}
		}
	}

	/**
	 * 下发计划：先置计划为执行中（防重试重复下发）→ 重跑安全包络校验（全局约束：生成/下发前） → 逐点调 energy-command
	 * 建指令写执行记录；单点业务失败仅记日志跳过，不中止整日计划。
	 */
	public int dispatch(Long planId) {
		EmsPlan plan = planMapper.selectById(planId);
		if (plan == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
		}
		if (plan.getStatus() != 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "计划状态非待执行: " + plan.getStatus());
		}
		if (deviceName == null || deviceName.isBlank()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "未配置下发设备 energyx.ems.device-name");
		}
		List<PlanPoint> points;
		try {
			points = writer.read(plan.getStationId(), plan.getPlanDate());
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
		}
		EmsConstraint constraint = constraintMapper
			.selectOne(new LambdaQueryWrapper<EmsConstraint>().eq(EmsConstraint::getTenantId, plan.getTenantId())
				.eq(EmsConstraint::getStationId, plan.getStationId()));
		if (constraint == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "未配置安全约束: stationId=" + plan.getStationId());
		}
		SafetyEnvelopeValidator.ValidationResult vr = validator.validate(points, constraint.getSocMin().doubleValue(),
				constraint.getSocMax().doubleValue(), constraint.getChargePowerMax().doubleValue(),
				constraint.getDischargePowerMax().doubleValue(),
				constraint.getTempMax() == null ? null : constraint.getTempMax().doubleValue());
		if (!vr.valid()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "安全包络校验未通过: " + String.join("; ", vr.rejections()));
		}
		plan.setStatus(1); // 执行中（先置位：下发途中失败后重试命中 CONFLICT，杜绝重复下发）
		planMapper.updateById(plan);
		int sent = 0;
		for (PlanPoint p : points) {
			if ("STANDBY".equals(p.action())) {
				continue;
			}
			Map<String, Object> params = new HashMap<>();
			params.put("action", p.action());
			params.put("power", p.powerKw());
			params.put("socTarget", p.socTarget());
			try {
				String paramsJson = JSON.writeValueAsString(params); // 列类型 JSON，必须真实 JSON
				String commandId = commandClient.dispatch(productKey, deviceName, p.action(), params, 0L);
				EmsExecutionRecord rec = new EmsExecutionRecord();
				rec.setTenantId(plan.getTenantId());
				rec.setPlanId(planId);
				rec.setCommandId(commandId);
				rec.setDeviceId(0L);
				rec.setAction(p.action());
				rec.setParams(paramsJson);
				execMapper.insert(rec);
				sent++;
			}
			catch (BusinessException e) {
				log.warn("下发点失败 time={} action={} msg={}", p.time(), p.action(), e.getMessage());
			}
			catch (JsonProcessingException e) {
				throw new BusinessException(ErrorCode.BAD_REQUEST, "下发参数序列化失败: " + e.getMessage());
			}
		}
		return sent;
	}

	public Page<EmsPlan> page(long pageNo, long pageSize, Long stationId) {
		return planMapper.selectPage(new Page<>(pageNo, pageSize),
				new LambdaQueryWrapper<EmsPlan>().eq(stationId != null, EmsPlan::getStationId, stationId)
					.orderByDesc(EmsPlan::getPlanDate));
	}

	public List<PlanPoint> getPoints(Long planId) {
		EmsPlan plan = planMapper.selectById(planId);
		if (plan == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
		}
		try {
			return writer.read(plan.getStationId(), plan.getPlanDate());
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
		}
	}

	private EmsStrategy resolveStrategy(Long stationId, Long strategyId) {
		if (strategyId != null) {
			return strategyMapper.selectById(strategyId);
		}
		return strategyMapper.selectOne(new LambdaQueryWrapper<EmsStrategy>().eq(EmsStrategy::getStationId, stationId)
			.eq(EmsStrategy::getStatus, 1)
			.orderByDesc(EmsStrategy::getPriority)
			.last("LIMIT 1"));
	}

	private PlanInput toInput(EmsStrategy strategy, EmsConstraint c, List<EmsElectricityPrice> prices) {
		return new PlanInput(strategy.getStrategyType(), strategy.getConfig(), prices.stream()
			.map(p -> new PriceTier(p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue()))
			.toList(), c.getSocMax().doubleValue() / 2, // 初始 SOC 取包络中点（后续可接影子实时值）
				c.getSocMin().doubleValue(), c.getSocMax().doubleValue(), c.getChargePowerMax().doubleValue(),
				c.getDischargePowerMax().doubleValue());
	}

}
