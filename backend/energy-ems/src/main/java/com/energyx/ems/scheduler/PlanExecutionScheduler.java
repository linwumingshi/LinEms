package com.energyx.ems.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.common.redis.DistributedLock;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.service.EmsPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 充放电计划执行调度器（P0 执行闭环核心）。
 *
 * <p>
 * 职责：把"执行中"（status=1）的计划按计划点时刻到点下发指令，取代旧的"一次性全量下发"缺陷行为。 每分钟触发一次（计划点粒度为 5
 * 分钟，分钟级轮询有充足余量）；分布式锁保证多实例仅一个执行。
 * </p>
 *
 * <p>
 * 调度边界：仅处理 plan_date = 今天的执行中计划；昨天遗留执行中计划（设备长期离线等）由 {@code settleYesterdayPlans}
 * 收尾推进为完成/失败，避免状态永久悬挂。
 * </p>
 */
@Slf4j
@Component
public class PlanExecutionScheduler {

	private final EmsPlanMapper planMapper;

	private final EmsPlanService planService;

	private final DistributedLock distributedLock;

	public PlanExecutionScheduler(EmsPlanMapper planMapper, EmsPlanService planService,
			DistributedLock distributedLock) {
		this.planMapper = planMapper;
		this.planService = planService;
		this.distributedLock = distributedLock;
	}

	/** 每分钟触发：到点下发 + 状态推进。锁 TTL 60s 覆盖全量执行中计划的点下发最坏耗时。 */
	@Scheduled(cron = "0 * * * * *")
	public void executeDuePlans() {
		distributedLock.runIfAcquired("scheduled:ems-plan-exec", 60, this::doExecuteDuePlans);
	}

	private void doExecuteDuePlans() {
		LocalDate today = LocalDate.now();
		// 今日执行中计划：到点下发当前槽位点，并尝试推进状态
		List<EmsPlan> running = planMapper
			.selectList(new LambdaQueryWrapper<EmsPlan>().eq(EmsPlan::getStatus, 1).eq(EmsPlan::getPlanDate, today));
		for (EmsPlan plan : running) {
			try {
				planService.dispatchDuePoints(plan);
				planService.refreshPlanStatus(plan.getPlanId());
			}
			catch (Exception e) {
				log.warn("[PlanExec] 今日计划调度失败 planId={} msg={}", plan.getPlanId(), e.getMessage());
			}
		}
		// 昨日遗留执行中计划：状态推进收尾（不补发，仅收敛状态）
		List<EmsPlan> stale = planMapper
			.selectList(new LambdaQueryWrapper<EmsPlan>().eq(EmsPlan::getStatus, 1).lt(EmsPlan::getPlanDate, today));
		for (EmsPlan plan : stale) {
			try {
				planService.refreshPlanStatus(plan.getPlanId());
			}
			catch (Exception e) {
				log.warn("[PlanExec] 昨日计划收尾失败 planId={} msg={}", plan.getPlanId(), e.getMessage());
			}
		}
	}

}
