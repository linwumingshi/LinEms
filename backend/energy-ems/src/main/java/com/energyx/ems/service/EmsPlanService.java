package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.redis.DistributedLock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划生成编排：生成 → 安全包络校验 → TDengine 点序列 → 计划头落库 → 下发（复用 energy-command）。 租户取自策略行（xxl-job
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

	private final DistributedLock distributedLock;

	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String productKey;

	@Value("${energyx.ems.device-name:}")
	private String deviceName;

	/** 执行记录 params 列为 MySQL JSON，必须真实 JSON 序列化（不能 Map.toString）。 */
	private static final ObjectMapper JSON = new ObjectMapper();

	/** 到点下发补发窗口（分钟）：调度器重启/延迟错过当前槽位时，仍可补发该窗口内的点 */
	private static final long DISPATCH_WINDOW_MIN = 10;

	public EmsPlanService(EmsStrategyMapper strategyMapper, EmsElectricityPriceMapper priceMapper,
			EmsConstraintMapper constraintMapper, EmsPlanMapper planMapper, EmsExecutionRecordMapper execMapper,
			SafetyEnvelopeValidator validator, TdenginePlanWriter writer, CommandClient commandClient,
			DistributedLock distributedLock) {
		this.strategyMapper = strategyMapper;
		this.priceMapper = priceMapper;
		this.constraintMapper = constraintMapper;
		this.planMapper = planMapper;
		this.execMapper = execMapper;
		this.validator = validator;
		this.writer = writer;
		this.commandClient = commandClient;
		this.distributedLock = distributedLock;
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
				.eq(EmsElectricityPrice::getStationId, stationId)
				.eq(EmsElectricityPrice::getStatus, 1)
				.le(EmsElectricityPrice::getValidFrom, planDate)
				.ge(EmsElectricityPrice::getValidTo, planDate)
				.orderByAsc(EmsElectricityPrice::getStartTime));
		boolean priceDriven = isPriceDriven(strategy.getConfig());
		if (priceDriven && prices.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "该电站 " + planDate + " 未配置生效的分时电价（status=1 且在有效期内）");
		}
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
		plan.setPlanType(derivePlanType(points));
		plan.setTotalEnergy(computeTotalEnergy(points));
		plan.setStatus(0); // 待执行
		plan.setPlanParam(priceDriven ? buildPriceDrivenParam(strategy.getConfig(), prices) : strategy.getConfig());
		planMapper.insert(plan);
		log.info("生成计划 planId={} stationId={} 点数={}", plan.getPlanId(), stationId, points.size());
		return plan;
	}

	/**
	 * 每日 00:05 生成次日计划（xxl-job 触发，admin cron=0 5 0 * * *）。定时线程无租户上下文，遍历全量启用策略。R-01
	 * 分布式锁：多实例仅一个实例执行，防重复生成/下发。
	 */
	@XxlJob("emsDailyPlanGenerate")
	public void generateDailyPlans() {
		// 锁 TTL 600s：覆盖全量策略生成+校验+TDengine 写的最坏耗时（100 电站级）
		distributedLock.runIfAcquired("scheduled:ems-daily-plan", 600, this::doGenerateDailyPlans);
	}

	private void doGenerateDailyPlans() {
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
	 * 下发计划（受理制）：校验待执行状态 → 重跑安全包络校验 → 置执行中 → 立即下发当前到期点， 其余点由
	 * {@link PlanExecutionScheduler} 每分钟按计划点时刻到点下发。 取代旧"一次性全量下发"缺陷：状态卡死 + 时间字段丢失。
	 * @param planId 计划 ID
	 * @return 本次立即下发的点数（受理成功即返回，不代表全量下发完成）
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
		List<PlanPoint> points = readPoints(plan.getStationId(), plan.getPlanDate());
		validateEnvelope(plan, points);
		// 受理：置执行中，后续由调度器到点下发（先置位防重复受理）
		plan.setStatus(1);
		planMapper.updateById(plan);
		int sent = dispatchDuePoints(plan);
		// 立即推进一次：全部点已错过窗口/已终态时马上收敛，不必等调度器下一轮
		refreshPlanStatus(planId);
		log.info("下发计划受理成功 planId={} stationId={} 立即下发点数={}", planId, plan.getStationId(), sent);
		return sent;
	}

	/**
	 * 到点下发：把计划点序列中「已到时刻」且未下发的点逐一下发（含补发窗口）， 写执行记录。 幂等：按 (plan_id, plan_time)
	 * 唯一键查重，调度器重复触发不会重复下发。
	 * @param plan 执行中计划（status=1）
	 * @return 本次新下发点数
	 */
	public int dispatchDuePoints(EmsPlan plan) {
		List<PlanPoint> points = readPoints(plan.getStationId(), plan.getPlanDate());
		LocalTime now = LocalTime.now();
		int sent = 0;
		for (PlanPoint p : points) {
			if ("STANDBY".equals(p.action())) {
				continue;
			}
			// 只处理已到时刻的点（当前槽位 + 前 10 分钟补发窗口，防调度器重启/延迟错过）
			if (p.time().isAfter(now)) {
				continue;
			}
			if (now.minusMinutes(DISPATCH_WINDOW_MIN).isAfter(p.time())) {
				continue;
			}
			if (execMapper.selectByPlanAndTime(plan.getPlanId(), p.time()) != null) {
				continue; // 该点已下发，跳过
			}
			Map<String, Object> params = new HashMap<>();
			params.put("action", p.action());
			params.put("power", p.powerKw());
			params.put("socTarget", p.socTarget());
			params.put("time", p.time().toString()); // 携带计划点时刻，供设备侧定时执行语义
			try {
				String paramsJson = JSON.writeValueAsString(params); // 列类型 JSON，必须真实 JSON
				String commandId = commandClient.dispatch(productKey, deviceName, p.action(), params, 0L);
				EmsExecutionRecord rec = new EmsExecutionRecord();
				rec.setTenantId(plan.getTenantId());
				rec.setPlanId(plan.getPlanId());
				rec.setCommandId(commandId);
				rec.setPlanTime(p.time());
				rec.setDeviceId(0L);
				rec.setAction(p.action());
				rec.setState(1); // 已下发，待 ACK
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

	/**
	 * 状态推进（调度器/ACK 回写后调用）：当计划全部点已下发且全部到终态时收敛状态。 全部成功 → 完成(2)；存在失败/超时 →
	 * 失败(4)；错过下发窗口（计划日已过，或今日所有点时刻已过补发窗口）的点补记超时，避免状态永久悬挂。
	 * @param planId 计划 ID
	 */
	public void refreshPlanStatus(Long planId) {
		EmsPlan plan = planMapper.selectById(planId);
		if (plan == null || plan.getStatus() != 1) {
			return; // 非执行中无需推进
		}
		List<PlanPoint> points = readPoints(plan.getStationId(), plan.getPlanDate());
		List<PlanPoint> actionable = points.stream().filter(p -> !"STANDBY".equals(p.action())).toList();
		List<EmsExecutionRecord> records = execMapper.selectByPlanId(planId);
		Set<LocalTime> dispatched = records.stream().map(EmsExecutionRecord::getPlanTime).collect(Collectors.toSet());
		boolean allDispatched = actionable.stream().allMatch(p -> dispatched.contains(p.time()));
		// 错过下发窗口：计划日已过，或今日所有点时刻均已过 10min 补发窗口（今日计划晚下发不再补发过期点）
		LocalTime now = LocalTime.now();
		boolean allPointsPassed = actionable.stream()
			.allMatch(p -> now.isAfter(p.time().plusMinutes(DISPATCH_WINDOW_MIN)));
		boolean planDateOver = LocalDate.now().isAfter(plan.getPlanDate()) || allPointsPassed;
		if (!allDispatched && planDateOver) {
			// 已过下发窗口但有点未下发：补记超时记录，收敛不悬挂
			for (PlanPoint p : actionable) {
				if (dispatched.contains(p.time())) {
					continue;
				}
				EmsExecutionRecord rec = new EmsExecutionRecord();
				rec.setTenantId(plan.getTenantId());
				rec.setPlanId(planId);
				rec.setCommandId("");
				rec.setPlanTime(p.time());
				rec.setDeviceId(0L);
				rec.setAction(p.action());
				rec.setState(4); // 超时（错过下发窗口）
				rec.setParams(null); // 未实际下发，无参数（JSON 列不允许空串，null 合法）
				execMapper.insert(rec);
			}
			records = execMapper.selectByPlanId(planId);
			allDispatched = true;
		}
		if (!allDispatched) {
			return; // 还有未到期/未下发的点，继续保持执行中
		}
		boolean allTerminal = records.stream().allMatch(r -> r.getState() != null && r.getState() >= 2);
		if (!allTerminal) {
			return; // 尚有在途点（已下发未回执），继续等待 ACK
		}
		boolean hasFailure = records.stream().anyMatch(r -> r.getState() >= 3);
		plan.setStatus(hasFailure ? 4 : 2);
		planMapper.updateById(plan);
		log.info("计划状态收敛 planId={} 状态={} 点数={} 失败={}", planId, plan.getStatus(), records.size(), hasFailure);
	}

	/** 查询计划执行记录（按计划点时刻升序，前端展示执行进度/结果） */
	public List<EmsExecutionRecord> records(Long planId) {
		EmsPlan plan = planMapper.selectById(planId);
		if (plan == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
		}
		return execMapper.selectByPlanId(planId);
	}

	private List<PlanPoint> readPoints(Long stationId, LocalDate planDate) {
		try {
			return writer.read(stationId, planDate);
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
		}
	}

	private void validateEnvelope(EmsPlan plan, List<PlanPoint> points) {
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
	}

	/**
	 * 分页查询充放电计划，支持按电站与状态筛选。
	 * @param pageNo 页码，从 1 开始
	 * @param pageSize 每页条数
	 * @param stationId 电站 ID，为 null 时不过滤
	 * @param status 计划状态，为 null 时不过滤
	 * @return 计划分页结果
	 */
	public Page<EmsPlan> page(long pageNo, long pageSize, Long stationId, Integer status) {
		return planMapper.selectPage(new Page<>(pageNo, pageSize),
				new LambdaQueryWrapper<EmsPlan>().eq(stationId != null, EmsPlan::getStationId, stationId)
					.eq(status != null, EmsPlan::getStatus, status)
					.orderByDesc(EmsPlan::getPlanDate));
	}

	public List<PlanPoint> getPoints(Long planId) {
		EmsPlan plan = planMapper.selectById(planId);
		if (plan == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
		}
		return readPoints(plan.getStationId(), plan.getPlanDate());
	}

	private EmsStrategy resolveStrategy(Long stationId, Long strategyId) {
		if (strategyId != null) {
			EmsStrategy s = strategyMapper.selectById(strategyId);
			// 显式指定策略时同样强制 status=1：草稿/停用策略不能生成计划（P0-5d）
			if (s != null && s.getStatus() != 1) {
				throw new BusinessException(ErrorCode.CONFLICT,
						"策略未启用（status=" + s.getStatus() + "），不能生成计划: strategyId=" + strategyId);
			}
			return s;
		}
		return strategyMapper.selectOne(new LambdaQueryWrapper<EmsStrategy>().eq(EmsStrategy::getStationId, stationId)
			.eq(EmsStrategy::getStatus, 1)
			.orderByDesc(EmsStrategy::getPriority)
			.last("LIMIT 1"));
	}

	private boolean isPriceDriven(String config) {
		try {
			return JSON.readTree(config).path("priceDriven").asBoolean(false);
		}
		catch (Exception e) {
			return false;
		}
	}

	/**
	 * priceDriven 计划：plan_param = { ...config,
	 * priceSnapshot:[{priceType,start,end,price}] }；序列化失败回退原 config。
	 */
	private String buildPriceDrivenParam(String config, List<EmsElectricityPrice> prices) {
		try {
			ObjectNode node = (ObjectNode) JSON.readTree(config);
			ArrayNode snapshot = node.putArray("priceSnapshot");
			for (EmsElectricityPrice p : prices) {
				ObjectNode tier = snapshot.addObject();
				tier.put("priceType", p.getPriceType());
				tier.put("start", p.getStartTime().toString());
				tier.put("end", p.getEndTime().toString());
				tier.put("price", p.getPrice().doubleValue());
			}
			return JSON.writeValueAsString(node);
		}
		catch (Exception e) {
			return config;
		}
	}

	/**
	 * 推导计划类型：1 纯充 / 2 纯放 / 3 混合（含充电与放电点）。无动作点（全待机/空计划）兜底为混合， 与历史落库语义一致。
	 */
	private static int derivePlanType(List<PlanPoint> points) {
		boolean charge = false;
		boolean discharge = false;
		for (PlanPoint p : points) {
			if ("CHARGE".equals(p.action())) {
				charge = true;
			}
			else if ("DISCHARGE".equals(p.action())) {
				discharge = true;
			}
		}
		return charge && discharge ? 3 : charge ? 1 : discharge ? 2 : 3;
	}

	/** 计划总量 kWh = Σ(点功率 × 点粒度时长)，仅计充/放动作点，待机点不计。 */
	private static BigDecimal computeTotalEnergy(List<PlanPoint> points) {
		double energyKwh = 0;
		for (PlanPoint p : points) {
			if ("STANDBY".equals(p.action()))
				continue;
			energyKwh += p.powerKw() * PlanGenerator.SLOT_MIN / 60.0;
		}
		return BigDecimal.valueOf(energyKwh).setScale(3, RoundingMode.HALF_UP);
	}

	private PlanInput toInput(EmsStrategy strategy, EmsConstraint c, List<EmsElectricityPrice> prices) {
		return new PlanInput(strategy.getStrategyType(), strategy.getConfig(), prices.stream()
			.map(p -> new PriceTier(p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue()))
			.toList(), c.getSocMax().doubleValue() / 2, // 初始 SOC 取包络中点（后续可接影子实时值）
				c.getSocMin().doubleValue(), c.getSocMax().doubleValue(), c.getChargePowerMax().doubleValue(),
				c.getDischargePowerMax().doubleValue());
	}

}
