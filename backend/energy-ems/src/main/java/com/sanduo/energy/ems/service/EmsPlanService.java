package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.entity.EmsElectricityPrice;
import com.sanduo.energy.ems.entity.EmsExecutionRecord;
import com.sanduo.energy.ems.entity.EmsPlan;
import com.sanduo.energy.ems.entity.EmsStrategy;
import com.sanduo.energy.ems.mapper.EmsConstraintMapper;
import com.sanduo.energy.ems.mapper.EmsElectricityPriceMapper;
import com.sanduo.energy.ems.mapper.EmsExecutionRecordMapper;
import com.sanduo.energy.ems.mapper.EmsPlanMapper;
import com.sanduo.energy.ems.mapper.EmsStrategyMapper;
import com.sanduo.energy.ems.util.PlanGenerator;
import com.sanduo.energy.ems.util.PlanInput;
import com.sanduo.energy.ems.util.PlanPoint;
import com.sanduo.energy.ems.util.PriceTier;
import com.sanduo.energy.ems.util.TdenginePlanWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划生成编排：生成 → 安全包络校验 → TDengine 点序列 → 计划头落库 → 下发（复用 energy-command）。
 * 租户取自策略行（@Scheduled 线程无请求租户上下文，见 [[multi-tenant-isolation]]）；
 * 约束/电价查询显式按该租户过滤，避免定时线程跨租户读到同 stationId 数据。
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

    @Value("${sanduo.ems.product-key:snd_ess_pcs}")
    private String productKey;

    @Value("${sanduo.ems.device-name:}")
    private String deviceName;

    public EmsPlanService(EmsStrategyMapper strategyMapper,
                          EmsElectricityPriceMapper priceMapper,
                          EmsConstraintMapper constraintMapper,
                          EmsPlanMapper planMapper,
                          EmsExecutionRecordMapper execMapper,
                          SafetyEnvelopeValidator validator,
                          TdenginePlanWriter writer,
                          CommandClient commandClient) {
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
        EmsConstraint constraint = constraintMapper.selectOne(new LambdaQueryWrapper<EmsConstraint>()
                .eq(EmsConstraint::getTenantId, tenant)
                .eq(EmsConstraint::getStationId, stationId));
        if (constraint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未配置安全约束: stationId=" + stationId);
        }
        List<EmsElectricityPrice> prices = priceMapper.selectList(new LambdaQueryWrapper<EmsElectricityPrice>()
                .eq(EmsElectricityPrice::getTenantId, tenant)
                .eq(EmsElectricityPrice::getStationId, stationId));
        List<PlanPoint> points = PlanGenerator.generate(toInput(strategy, constraint, prices));
        SafetyEnvelopeValidator.ValidationResult vr = validator.validate(points,
                constraint.getSocMin().doubleValue(),
                constraint.getSocMax().doubleValue(),
                constraint.getChargePowerMax().doubleValue(),
                constraint.getDischargePowerMax().doubleValue(),
                constraint.getTempMax() == null ? null : constraint.getTempMax().doubleValue());
        if (!vr.valid()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "安全包络校验未通过: " + String.join("; ", vr.rejections()));
        }
        try {
            writer.write(stationId, planDate, points);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "TDengine 写入失败: " + e.getMessage());
        }
        EmsPlan plan = new EmsPlan();
        plan.setTenantId(strategy.getTenantId());
        plan.setStationId(stationId);
        plan.setStrategyId(strategy.getStrategyId());
        plan.setPlanDate(planDate);
        plan.setPlanType(3); // 混合
        plan.setStatus(0);  // 待执行
        plan.setPlanParam(strategy.getConfig());
        planMapper.insert(plan);
        log.info("生成计划 planId={} stationId={} 点数={}", plan.getPlanId(), stationId, points.size());
        return plan;
    }

    /** 下发计划：逐点调 energy-command 建指令 → 写执行记录 → 计划头置为执行中。 */
    public int dispatch(Long planId) {
        EmsPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
        }
        if (plan.getStatus() != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "计划状态非待执行: " + plan.getStatus());
        }
        if (deviceName == null || deviceName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未配置下发设备 sanduo.ems.device-name");
        }
        List<PlanPoint> points;
        try {
            points = writer.read(plan.getStationId(), plan.getPlanDate());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
        }
        int sent = 0;
        for (PlanPoint p : points) {
            if ("STANDBY".equals(p.action())) {
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("action", p.action());
            params.put("power", p.powerKw());
            params.put("socTarget", p.socTarget());
            String commandId = commandClient.dispatch(productKey, deviceName, p.action(), params, 0L);
            EmsExecutionRecord rec = new EmsExecutionRecord();
            rec.setTenantId(plan.getTenantId());
            rec.setPlanId(planId);
            rec.setCommandId(commandId);
            rec.setDeviceId(0L);
            rec.setAction(p.action());
            rec.setParams(params.toString());
            execMapper.insert(rec);
            sent++;
        }
        plan.setStatus(1); // 执行中
        planMapper.updateById(plan);
        return sent;
    }

    public Page<EmsPlan> page(long pageNo, long pageSize, Long stationId) {
        return planMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EmsPlan>()
                        .eq(stationId != null, EmsPlan::getStationId, stationId)
                        .orderByDesc(EmsPlan::getPlanDate));
    }

    public List<PlanPoint> getPoints(Long planId) {
        EmsPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
        }
        try {
            return writer.read(plan.getStationId(), plan.getPlanDate());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
        }
    }

    private EmsStrategy resolveStrategy(Long stationId, Long strategyId) {
        if (strategyId != null) {
            return strategyMapper.selectById(strategyId);
        }
        return strategyMapper.selectOne(new LambdaQueryWrapper<EmsStrategy>()
                .eq(EmsStrategy::getStationId, stationId)
                .eq(EmsStrategy::getStatus, 1)
                .orderByDesc(EmsStrategy::getPriority)
                .last("LIMIT 1"));
    }

    private PlanInput toInput(EmsStrategy strategy, EmsConstraint c, List<EmsElectricityPrice> prices) {
        return new PlanInput(
                strategy.getStrategyType(),
                strategy.getConfig(),
                prices.stream().map(p -> new PriceTier(
                        p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue())).toList(),
                c.getSocMax().doubleValue() / 2, // 初始 SOC 取包络中点（后续可接影子实时值）
                c.getSocMin().doubleValue(),
                c.getSocMax().doubleValue(),
                c.getChargePowerMax().doubleValue(),
                c.getDischargePowerMax().doubleValue());
    }
}
