package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsStationMetaMapper;
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

/** EmsStationMetaService.upsert 幂等：同站已存在原位更新，否则插入。 */
class EmsStationMetaServiceTest {

	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsStationMeta.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsStationMetaService newService(EmsStationMetaMapper mapper) {
		EmsStationMetaService svc = new EmsStationMetaService();
		ReflectionTestUtils.setField(svc, "baseMapper", mapper);
		return svc;
	}

	@Test
	void upsert_firstInsertSetsTenantAndNoPk() {
		EmsStationMetaMapper mapper = mock(EmsStationMetaMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);
		EmsStationMetaService svc = newService(mapper);

		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("1000000"));
		svc.upsert(meta);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsStationMeta> captor = ArgumentCaptor.forClass(EmsStationMeta.class);
		verify(mapper).insert(captor.capture());
		verify(mapper, never()).updateById(any(EmsStationMeta.class));
		assertNull(captor.getValue().getStationMetaId());
		assertEquals(7L, captor.getValue().getTenantId());
	}

	@Test
	void upsert_resubmitUpdatesInPlace() {
		EmsStationMeta existing = new EmsStationMeta();
		existing.setStationMetaId(1L);
		existing.setStationId(10L);
		EmsStationMetaMapper mapper = mock(EmsStationMetaMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(existing);
		EmsStationMetaService svc = newService(mapper);

		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("2000000"));
		svc.upsert(meta);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsStationMeta> upd = ArgumentCaptor.forClass(EmsStationMeta.class);
		verify(mapper).updateById(upd.capture());
		assertEquals(1L, upd.getValue().getStationMetaId()); // 原位更新，保留主键
		assertEquals(0, new BigDecimal("2000000").compareTo(upd.getValue().getInvestmentAmount()));
	}

}
