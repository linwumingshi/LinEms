package com.energyx.ems.service;

import com.energyx.common.redis.DistributedLock;
import com.energyx.common.exception.BusinessException;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsExecutionRecord;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsExecutionRecordMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.util.TdenginePlanWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmsPlanServiceTest {

	@Test
	void generate_createsPlanAndWritesPoints() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);
		SafetyEnvelopeValidator validator = new SafetyEnvelopeValidator();
		TdenginePlanWriter writer = mock(TdenginePlanWriter.class);
		CommandClient commandClient = mock(CommandClient.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setConfig("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}],"
				+ "\"socRange\":{\"min\":10,\"max\":90}}");
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);
		when(priceMapper.selectList(any())).thenReturn(List.of());

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				validator, writer, commandClient, new DistributedLock(mock(StringRedisTemplate.class)));
		EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		assertNotNull(plan);
		assertEquals(7L, plan.getTenantId()); // 租户取自策略行
		verify(planMapper).insert(any(EmsPlan.class)); // 计划头落库
		verify(writer).write(eq(10L), eq(LocalDate.of(2026, 8, 8)), anyList()); // 点序列写
																				// TDengine
	}

	@Test
	void dispatch_rejectsNonPendingPlan() throws Exception {
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setStatus(1); // 已执行中 → 拒绝
		when(planMapper.selectById(1L)).thenReturn(plan);

		EmsPlanService svc = new EmsPlanService(mock(EmsStrategyMapper.class), mock(EmsElectricityPriceMapper.class),
				mock(EmsConstraintMapper.class), planMapper, mock(EmsExecutionRecordMapper.class),
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)));
		ReflectionTestUtils.setField(svc, "deviceName", "ess-dev-01");

		assertThrows(BusinessException.class, () -> svc.dispatch(1L));
	}

	@Test
	void dispatch_acceptsPlanAndDispatchesDuePoints() throws Exception {
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);
		TdenginePlanWriter writer = mock(TdenginePlanWriter.class);
		CommandClient commandClient = mock(CommandClient.class);

		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setTenantId(7L);
		plan.setStationId(10L);
		plan.setPlanDate(LocalDate.now());
		plan.setStatus(0); // 待执行
		when(planMapper.selectById(1L)).thenReturn(plan);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);

		// 点序列：一个已到时刻的点（触发下发）+ 一个未来点（调度器稍后处理）
		when(writer.read(eq(10L), any(LocalDate.class)))
			.thenReturn(List.of(new PlanPoint(LocalTime.now().minusMinutes(1), "CHARGE", 50, 40),
					new PlanPoint(LocalTime.now().plusHours(2), "DISCHARGE", 60, 50)));
		when(execMapper.selectByPlanAndTime(anyLong(), any())).thenReturn(null);
		when(execMapper.selectByPlanId(1L)).thenReturn(List.of()); // 受理后状态推进：尚无记录，未到收敛条件
		when(commandClient.dispatch(anyString(), anyString(), anyString(), anyMap(), anyLong())).thenReturn("cmd-1001");

		EmsPlanService svc = new EmsPlanService(mock(EmsStrategyMapper.class), mock(EmsElectricityPriceMapper.class),
				constraintMapper, planMapper, execMapper, new SafetyEnvelopeValidator(), writer, commandClient,
				new DistributedLock(mock(StringRedisTemplate.class)));
		ReflectionTestUtils.setField(svc, "deviceName", "ess-dev-01");
		ReflectionTestUtils.setField(svc, "productKey", "snd_ess_pcs");

		int sent = svc.dispatch(1L);

		assertEquals(1, sent); // 仅到点的一个点被下发
		verify(planMapper).updateById(any(EmsPlan.class)); // 状态置执行中
		verify(commandClient, times(1)).dispatch(anyString(), anyString(), eq("CHARGE"), anyMap(), anyLong());
		verify(execMapper)
			.insert(ArgumentMatchers.<EmsExecutionRecord>argThat(r -> r.getPlanTime() != null && r.getState() == 1)); // 执行记录带点时刻
	}

	@Test
	void refreshPlanStatus_marksCompletedWhenAllPointsTerminal() throws Exception {
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);
		TdenginePlanWriter writer = mock(TdenginePlanWriter.class);

		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setTenantId(7L);
		plan.setStationId(10L);
		plan.setPlanDate(LocalDate.now());
		plan.setStatus(1); // 执行中
		when(planMapper.selectById(1L)).thenReturn(plan);

		when(writer.read(eq(10L), any(LocalDate.class)))
			.thenReturn(List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 50, 40)));
		EmsExecutionRecord rec = new EmsExecutionRecord();
		rec.setPlanId(1L);
		rec.setPlanTime(LocalTime.of(2, 0));
		rec.setState(2); // 成功
		when(execMapper.selectByPlanId(1L)).thenReturn(List.of(rec));

		EmsPlanService svc = new EmsPlanService(mock(EmsStrategyMapper.class), mock(EmsElectricityPriceMapper.class),
				mock(EmsConstraintMapper.class), planMapper, execMapper, new SafetyEnvelopeValidator(), writer,
				mock(CommandClient.class), new DistributedLock(mock(StringRedisTemplate.class)));

		svc.refreshPlanStatus(1L);

		assertEquals(2, plan.getStatus()); // 完成
		verify(planMapper).updateById(plan);
	}

	@Test
	void refreshPlanStatus_marksFailedWhenAnyPointFailed() throws Exception {
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);
		TdenginePlanWriter writer = mock(TdenginePlanWriter.class);

		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setTenantId(7L);
		plan.setStationId(10L);
		plan.setPlanDate(LocalDate.now());
		plan.setStatus(1);
		when(planMapper.selectById(1L)).thenReturn(plan);

		when(writer.read(eq(10L), any(LocalDate.class)))
			.thenReturn(List.of(new PlanPoint(LocalTime.of(2, 0), "CHARGE", 50, 40)));
		EmsExecutionRecord rec = new EmsExecutionRecord();
		rec.setPlanId(1L);
		rec.setPlanTime(LocalTime.of(2, 0));
		rec.setState(3); // 失败
		when(execMapper.selectByPlanId(1L)).thenReturn(List.of(rec));

		EmsPlanService svc = new EmsPlanService(mock(EmsStrategyMapper.class), mock(EmsElectricityPriceMapper.class),
				mock(EmsConstraintMapper.class), planMapper, execMapper, new SafetyEnvelopeValidator(), writer,
				mock(CommandClient.class), new DistributedLock(mock(StringRedisTemplate.class)));

		svc.refreshPlanStatus(1L);

		assertEquals(4, plan.getStatus()); // 失败
	}

}
