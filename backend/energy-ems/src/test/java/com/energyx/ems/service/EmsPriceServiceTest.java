package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EmsPriceService.batchSave 的 upsert 幂等语义：同站同 startTime 原位更新，重复提交不叠行。 */
class EmsPriceServiceTest {

	/** LambdaQueryWrapper.getSqlSegment() 需实体表元信息（列名映射）；纯 Mockito 无 Spring，手动注册。 */
	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				EmsElectricityPrice.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsElectricityPrice tier(LocalTime start, LocalTime end, BigDecimal price) {
		EmsElectricityPrice p = new EmsElectricityPrice();
		p.setStationId(10L);
		p.setStartTime(start);
		p.setEndTime(end);
		p.setPrice(price);
		return p;
	}

	private static EmsPriceService newService(EmsElectricityPriceMapper mapper) {
		EmsPriceService svc = new EmsPriceService();
		ReflectionTestUtils.setField(svc, "baseMapper", mapper);
		return svc;
	}

	@Test
	void batchSave_firstSubmitInsertsAll() {
		EmsElectricityPriceMapper mapper = mock(EmsElectricityPriceMapper.class);
		when(mapper.selectList(any())).thenReturn(List.of()); // 无现有档位
		EmsPriceService svc = newService(mapper);

		svc.batchSave(List.of(tier(LocalTime.of(0, 0), LocalTime.of(8, 0), new BigDecimal("0.3")),
				tier(LocalTime.of(8, 0), LocalTime.of(11, 0), new BigDecimal("1.2"))));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsElectricityPrice> captor = ArgumentCaptor.forClass(EmsElectricityPrice.class);
		verify(mapper, times(2)).insert(captor.capture());
		verify(mapper, never()).updateById(any(EmsElectricityPrice.class));
		for (EmsElectricityPrice p : captor.getAllValues()) {
			assertNull(p.getPriceId()); // 新增不携带主键
			assertEquals(7L, p.getTenantId()); // 补租户
			assertEquals(1, p.getStatus()); // status 缺省置 1
		}
	}

	@Test
	void batchSave_resubmitUpdatesInPlaceNoDuplicate() {
		EmsElectricityPrice existing = tier(LocalTime.of(0, 0), LocalTime.of(8, 0), new BigDecimal("0.3"));
		existing.setPriceId(1L);
		EmsElectricityPriceMapper mapper = mock(EmsElectricityPriceMapper.class);
		when(mapper.selectList(any())).thenReturn(List.of(existing)); // 已入库一档
		EmsPriceService svc = newService(mapper);

		// 重交同档（改价 0.5）+ 新档（11:00-14:00）
		EmsElectricityPrice changed = tier(LocalTime.of(0, 0), LocalTime.of(8, 0), new BigDecimal("0.5"));
		EmsElectricityPrice fresh = tier(LocalTime.of(11, 0), LocalTime.of(14, 0), new BigDecimal("0.6"));
		svc.batchSave(List.of(changed, fresh));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsElectricityPrice> upd = ArgumentCaptor.forClass(EmsElectricityPrice.class);
		verify(mapper).updateById(upd.capture());
		assertEquals(1L, upd.getValue().getPriceId()); // 原位更新，保留原主键
		assertEquals(0, new BigDecimal("0.5").compareTo(upd.getValue().getPrice()));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsElectricityPrice> ins = ArgumentCaptor.forClass(EmsElectricityPrice.class);
		verify(mapper).insert(ins.capture());
		assertEquals(LocalTime.of(11, 0), ins.getValue().getStartTime()); // 仅新档插入
	}

	@Test
	void delete_removesExisting() {
		EmsElectricityPriceMapper mapper = mock(EmsElectricityPriceMapper.class);
		when(mapper.deleteById(1L)).thenReturn(1);
		EmsPriceService svc = newService(mapper);

		svc.delete(1L);

		verify(mapper).deleteById(1L);
	}

	@Test
	void delete_missingThrows() {
		EmsElectricityPriceMapper mapper = mock(EmsElectricityPriceMapper.class);
		when(mapper.deleteById(99L)).thenReturn(0);
		EmsPriceService svc = newService(mapper);

		assertThrows(BusinessException.class, () -> svc.delete(99L));
	}

	@Test
	void batchSave_tenantScopedLookup() {
		EmsElectricityPriceMapper mapper = mock(EmsElectricityPriceMapper.class);
		when(mapper.selectList(any())).thenReturn(List.of());
		EmsPriceService svc = newService(mapper);

		svc.batchSave(List.of(tier(LocalTime.of(0, 0), LocalTime.of(8, 0), new BigDecimal("0.3"))));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaQueryWrapper<EmsElectricityPrice>> captor = ArgumentCaptor
			.forClass(LambdaQueryWrapper.class);
		verify(mapper).selectList(captor.capture());
		String sql = captor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"));
		assertTrue(sql.contains("station_id")); // 现有一档按站过滤，不跨站覆盖
	}

}
