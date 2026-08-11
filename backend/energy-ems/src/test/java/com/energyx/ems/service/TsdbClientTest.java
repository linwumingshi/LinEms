package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.TsdbFeignClient;
import com.energyx.ems.client.TsdbHistoryRecordDto;
import com.energyx.ems.client.TsdbHistoryViewDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TsdbClient.history 解析/分页/失败降级：任一步失败返回空列表，不抛异常。 */
class TsdbClientTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private static TsdbHistoryRecordDto row(long ts, Double power, Integer runMode) {
		TsdbHistoryRecordDto r = new TsdbHistoryRecordDto();
		r.setTs(ts);
		Map<String, Object> values = new LinkedHashMap<>();
		if (power != null)
			values.put("power", power);
		if (runMode != null)
			values.put("runMode", runMode);
		r.setValues(values);
		return r;
	}

	private static TsdbHistoryViewDto view(long total, List<TsdbHistoryRecordDto> records) {
		TsdbHistoryViewDto v = new TsdbHistoryViewDto();
		v.setTotal(total);
		v.setRecords(records);
		return v;
	}

	private static TsdbClient newClient(TsdbFeignClient feign) {
		return new TsdbClient(feign);
	}

	@Test
	void history_parsesPowerAndRunMode() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(Result.ok(view(1, List.of(row(start, 60.0, 1)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1, rows.size());
		assertEquals(60.0, rows.get(0).power());
		assertEquals(1, rows.get(0).runMode());
		assertEquals(start, rows.get(0).ts());
	}

	@Test
	void history_missingValuesMapsNull() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(Result.ok(view(1, List.of(row(start, null, null)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1, rows.size());
		assertNull(rows.get(0).power());
		assertNull(rows.get(0).runMode());
	}

	@Test
	void history_businessErrorReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(Result.fail(com.energyx.common.exception.ErrorCode.PARAM_INVALID, "bad"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_feignThrowsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenThrow(new RuntimeException("connection refused"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_paginatesUntilTotalCovered() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		// 两页：page1 满 1000 行，page2 余 2 行，total=1002
		java.util.ArrayList<TsdbHistoryRecordDto> page1 = new java.util.ArrayList<>();
		for (int i = 0; i < 1000; i++)
			page1.add(row(start + i * 60_000L, 50.0, 2));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(1), eq(1000)))
			.thenReturn(Result.ok(view(1002, page1)));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000)))
			.thenReturn(Result
				.ok(view(1002, List.of(row(start + 1000 * 60_000L, 40.0, 1), row(start + 1001 * 60_000L, 40.0, 1)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1002, rows.size());
		verify(feign).history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000));
	}

	@Test
	void history_emptyRecordsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(Result.ok(view(0, List.of())));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_page2FailureReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		// 两页：page1 满 1000 行 total=2000，page2 业务失败 → 返回空列表而非部分 1000 行
		java.util.ArrayList<TsdbHistoryRecordDto> page1 = new java.util.ArrayList<>();
		for (int i = 0; i < 1000; i++)
			page1.add(row(start + i * 60_000L, 50.0, 2));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(1), eq(1000)))
			.thenReturn(Result.ok(view(2000, page1)));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000)))
			.thenReturn(Result.fail(com.energyx.common.exception.ErrorCode.PARAM_INVALID, "boom"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_nullResultReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(null);
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_nullRecordsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(),
				anyInt()))
			.thenReturn(Result.ok(view(0, null)));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

}
