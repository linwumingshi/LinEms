package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.redis.DistributedLock;
import com.energyx.common.exception.BusinessException;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsExecutionRecord;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStrategy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsExecutionRecordMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import com.energyx.ems.mqtt.EmsKafkaProducer;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.util.TdenginePlanWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

	/**
	 * LambdaQueryWrapper.getSqlSegment() 需实体表元信息（列名映射）；纯 Mockito 无 Spring 启动， 手动注册
	 * EmsElectricityPrice 的 TableInfo，供电价过滤断言取 SQL 段。
	 */
	@BeforeAll
	static void registerElectricityPriceTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				EmsElectricityPrice.class);
	}

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
		EmsKafkaProducer kafkaProducer = mock(EmsKafkaProducer.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(1); // 启用态（status=1）才可生成（P0-5d）
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
				validator, writer, commandClient, new DistributedLock(mock(StringRedisTemplate.class)), kafkaProducer);
		EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		assertNotNull(plan);
		assertEquals(7L, plan.getTenantId()); // 租户取自策略行
		assertEquals(3, plan.getPlanType()); // 混合：充(02-06)+放(18-22)窗口都有点
		// 总量 = 充 48×100×5/60 + 放 48×80×5/60 = 400 + 320 = 720 kWh
		assertEquals(0, new BigDecimal("720.000").compareTo(plan.getTotalEnergy()));
		verify(planMapper).insert(any(EmsPlan.class)); // 计划头落库
		verify(writer).write(eq(10L), eq(LocalDate.of(2026, 8, 8)), anyList()); // 点序列写
																				// TDengine
		// ems-plan 事件：key=stationId，payload 标记 PLAN_GENERATED（P0-8）
		verify(kafkaProducer).send(eq(KafkaTopicConstant.EMS_PLAN), eq("10"), contains("PLAN_GENERATED"));
	}

	@Test
	void generate_chargeOnlyPlanDerivesPureChargeTypeAndEnergy() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(1);
		s.setConfig("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"04:00\",\"powerLimit\":100}]}"); // 仅充电窗口
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);
		when(priceMapper.selectList(any())).thenReturn(List.of());

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));

		EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		assertEquals(1, plan.getPlanType()); // 纯充
		// 02:00-04:00 = 24 个 5 分钟槽 × 100kW × 5/60 = 200 kWh
		assertEquals(0, new BigDecimal("200.000").compareTo(plan.getTotalEnergy()));
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
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));
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
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));
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
				mock(CommandClient.class), new DistributedLock(mock(StringRedisTemplate.class)),
				mock(EmsKafkaProducer.class));

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
				mock(CommandClient.class), new DistributedLock(mock(StringRedisTemplate.class)),
				mock(EmsKafkaProducer.class));

		svc.refreshPlanStatus(1L);

		assertEquals(4, plan.getStatus()); // 失败
	}

	@Test
	void generate_priceDrivenWithoutEffectivePricesThrows() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(1); // 启用态（status=1）才可生成（P0-5d）
		s.setConfig("{\"priceDriven\":true,\"chargePower\":80}");
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);
		when(priceMapper.selectList(any())).thenReturn(List.of()); // 无生效电价

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));

		BusinessException ex = assertThrows(BusinessException.class,
				() -> svc.generate(10L, 1L, LocalDate.of(2026, 8, 8)));
		assertTrue(ex.getMessage().contains("未配置生效的分时电价"));
	}

	@Test
	void generate_priceDrivenWritesPriceSnapshotParam() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(1); // 启用态（status=1）才可生成（P0-5d）
		s.setConfig("{\"priceDriven\":true,\"chargePower\":80}");
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);

		EmsElectricityPrice p = new EmsElectricityPrice();
		p.setPriceType("VALLEY");
		p.setStartTime(LocalTime.of(0, 0));
		p.setEndTime(LocalTime.of(8, 0));
		p.setPrice(new BigDecimal("0.3"));
		when(priceMapper.selectList(any())).thenReturn(List.of(p));

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));

		EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		assertNotNull(plan.getPlanParam());
		assertTrue(plan.getPlanParam().contains("priceSnapshot"));
		assertTrue(plan.getPlanParam().contains("VALLEY"));
	}

	@Test
	void generate_filtersPricesByStatusAndValidity() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
		EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(1); // 启用态（status=1）才可生成（P0-5d）
		s.setConfig("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"04:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"20:00\",\"powerLimit\":80}]}"); // 手工模式
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsConstraint constraint = new EmsConstraint();
		constraint.setSocMin(new BigDecimal("10"));
		constraint.setSocMax(new BigDecimal("90"));
		constraint.setChargePowerMax(new BigDecimal("100"));
		constraint.setDischargePowerMax(new BigDecimal("80"));
		when(constraintMapper.selectOne(any())).thenReturn(constraint);
		when(priceMapper.selectList(any())).thenReturn(List.of()); // 手工模式：电价空不报错

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));

		svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaQueryWrapper<EmsElectricityPrice>> captor = ArgumentCaptor
			.forClass(LambdaQueryWrapper.class);
		verify(priceMapper).selectList(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("status"));
		assertTrue(sql.contains("valid_from"));
		assertTrue(sql.contains("valid_to"));
	}

	@Test
	void generate_rejectsDraftStrategy() throws Exception {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsStrategy s = new EmsStrategy();
		s.setStrategyId(1L);
		s.setStationId(10L);
		s.setTenantId(7L);
		s.setStrategyType("PEAK_VALLEY");
		s.setStatus(0); // 草稿 → 显式指定也拒绝
		when(stratMapper.selectById(1L)).thenReturn(s);

		EmsPlanService svc = new EmsPlanService(stratMapper, mock(EmsElectricityPriceMapper.class),
				mock(EmsConstraintMapper.class), mock(EmsPlanMapper.class), mock(EmsExecutionRecordMapper.class),
				new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
				new DistributedLock(mock(StringRedisTemplate.class)), mock(EmsKafkaProducer.class));

		BusinessException ex = assertThrows(BusinessException.class,
				() -> svc.generate(10L, 1L, LocalDate.of(2026, 8, 8)));
		assertTrue(ex.getMessage().contains("未启用"));
	}

}
