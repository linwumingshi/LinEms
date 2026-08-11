package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.mapper.EmsDemandConfigMapper;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/** EmsDemandConfigService.upsert 幂等：同站已存在原位更新，否则插入补租户；缺租户上下文抛异常。 */
class EmsDemandConfigServiceTest {

	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				EmsDemandConfig.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsDemandConfigService newService(EmsDemandConfigMapper mapper) {
		EmsDemandConfigService svc = new EmsDemandConfigService();
		ReflectionTestUtils.setField(svc, "baseMapper", mapper);
		return svc;
	}

	@Test
	void getByStation_missingTenantThrows() {
		TenantContext.clear();
		EmsDemandConfigService svc = newService(mock(EmsDemandConfigMapper.class));
		assertThrows(BusinessException.class, () -> svc.getByStation(10L));
	}

	@Test
	void upsert_firstInsertSetsTenantAndNoPk() {
		EmsDemandConfigMapper mapper = mock(EmsDemandConfigMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);
		EmsDemandConfigService svc = newService(mapper);

		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("1200.00"));
		svc.upsert(cfg);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandConfig> captor = ArgumentCaptor.forClass(EmsDemandConfig.class);
		verify(mapper).insert(captor.capture());
		verify(mapper, never()).updateById(any(EmsDemandConfig.class));
		assertNull(captor.getValue().getDemandConfigId());
		assertEquals(7L, captor.getValue().getTenantId());
	}

	@Test
	void upsert_resubmitUpdatesInPlace() {
		EmsDemandConfig existing = new EmsDemandConfig();
		existing.setDemandConfigId(1L);
		existing.setStationId(10L);
		EmsDemandConfigMapper mapper = mock(EmsDemandConfigMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(existing);
		EmsDemandConfigService svc = newService(mapper);

		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("1500.00"));
		svc.upsert(cfg);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandConfig> upd = ArgumentCaptor.forClass(EmsDemandConfig.class);
		verify(mapper).updateById(upd.capture());
		assertEquals(1L, upd.getValue().getDemandConfigId()); // 原位更新，保留主键
		assertEquals(0, new BigDecimal("1500.00").compareTo(upd.getValue().getDemandLimitKw()));
	}

}
