package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.EmsDemandRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EmsDemandRecordService 读写：查询按租户+站+窗过滤，upsert 透传 mapper。 */
class EmsDemandRecordServiceTest {

	@Test
	void getByStationAndWindow_returnsHit() {
		EmsDemandRecordMapper mapper = mock(EmsDemandRecordMapper.class);
		EmsDemandRecord hit = new EmsDemandRecord();
		hit.setWindowStart(LocalDateTime.of(2026, 8, 11, 10, 30));
		when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(hit);
		EmsDemandRecordService svc = new EmsDemandRecordService(mapper);

		EmsDemandRecord got = svc.getByStationAndWindow(7L, 10L, LocalDateTime.of(2026, 8, 11, 10, 30));
		assertSame(hit, got);
	}

	@Test
	void upsert_passesThroughToMapper() {
		EmsDemandRecordMapper mapper = mock(EmsDemandRecordMapper.class);
		EmsDemandRecordService svc = new EmsDemandRecordService(mapper);

		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(7L);
		rec.setStationId(10L);
		rec.setDemandKw(new BigDecimal("1200.00"));
		rec.setOverLimit(true);
		rec.setAction("SHED");
		EmsDemandRecord returned = svc.upsert(rec);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandRecord> captor = ArgumentCaptor.forClass(EmsDemandRecord.class);
		verify(mapper).upsert(captor.capture());
		assertSame(rec, returned);
		assertEquals("SHED", captor.getValue().getAction());
	}

}
