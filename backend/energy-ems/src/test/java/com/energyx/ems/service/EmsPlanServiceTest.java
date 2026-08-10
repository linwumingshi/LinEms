package com.energyx.ems.service;

import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsExecutionRecordMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import com.energyx.ems.util.TdenginePlanWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
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
		when(priceMapper.selectList(any())).thenReturn(java.util.List.of());

		EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
				validator, writer, commandClient, new com.energyx.common.redis.DistributedLock(
						org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class)));
		EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

		assertNotNull(plan);
		assertEquals(7L, plan.getTenantId()); // 租户取自策略行
		verify(planMapper).insert(any(EmsPlan.class)); // 计划头落库
		verify(writer).write(eq(10L), eq(LocalDate.of(2026, 8, 8)), anyList()); // 点序列写
																				// TDengine
	}

}
