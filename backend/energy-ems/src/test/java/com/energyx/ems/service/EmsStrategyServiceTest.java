package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.enums.StrategyStatus;
import com.energyx.common.enums.StrategyType;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsConstraint;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.mapper.EmsConstraintMapper;
import com.energyx.ems.mapper.EmsStrategyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EmsStrategyService 保存即校验 config（P0-5c）：结构错误 / 功率超包络抛 BAD_REQUEST。 */
class EmsStrategyServiceTest {

	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsStrategy.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsConstraint.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsStrategyService newService(EmsStrategyMapper stratMapper, EmsConstraintMapper constraintMapper) {
		EmsStrategyService svc = new EmsStrategyService(constraintMapper);
		ReflectionTestUtils.setField(svc, "baseMapper", stratMapper);
		return svc;
	}

	private static EmsStrategy pvStrategy(String config) {
		EmsStrategy s = new EmsStrategy();
		s.setStationId(10L);
		s.setStrategyType(StrategyType.PEAK_VALLEY);
		s.setConfig(config);
		return s;
	}

	private static EmsConstraint constraint(BigDecimal chargeMax, BigDecimal dischargeMax) {
		EmsConstraint c = new EmsConstraint();
		c.setChargePowerMax(chargeMax);
		c.setDischargePowerMax(dischargeMax);
		return c;
	}

	@Test
	void create_withInvalidConfigThrows() {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsStrategyService svc = newService(stratMapper, constraintMapper);

		BusinessException ex = assertThrows(BusinessException.class,
				() -> svc.create(pvStrategy("{\"chargeWindows\":[],\"dischargeWindows\":[]}")));
		assertTrue(ex.getMessage().contains("至少配置一个充电或放电窗口"));
		verify(stratMapper, never()).insert(any(EmsStrategy.class));
	}

	@Test
	void create_withPowerExceedingEnvelopeThrows() {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		when(constraintMapper.selectOne(any())).thenReturn(constraint(new BigDecimal("100"), new BigDecimal("80")));
		EmsStrategyService svc = newService(stratMapper, constraintMapper);

		BusinessException ex = assertThrows(BusinessException.class, () -> svc.create(pvStrategy(
				"{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":150}],\"dischargeWindows\":[]}")));
		assertTrue(ex.getMessage().contains("超过安全包络上限"));
	}

	@Test
	void create_withValidConfigSaves() {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		when(constraintMapper.selectOne(any())).thenReturn(constraint(new BigDecimal("100"), new BigDecimal("80")));
		EmsStrategyService svc = newService(stratMapper, constraintMapper);

		EmsStrategy s = pvStrategy("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
				+ "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}]}");
		svc.create(s);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsStrategy> captor = ArgumentCaptor.forClass(EmsStrategy.class);
		verify(stratMapper).insert(captor.capture());
		assertEquals(7L, captor.getValue().getTenantId());
		assertEquals(StrategyStatus.DRAFT, captor.getValue().getStatus()); // 新建即草稿
	}

	@Test
	void update_withInvalidConfigThrows() {
		EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
		EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
		EmsStrategyService svc = newService(stratMapper, constraintMapper);

		EmsStrategy s = pvStrategy("not json");
		s.setStrategyId(1L);
		BusinessException ex = assertThrows(BusinessException.class, () -> svc.update(s));
		assertTrue(ex.getMessage().contains("不是合法 JSON"));
	}

}
