package com.energyx.ems.service;

import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.model.PcsDevice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DemandShaveClient 削峰下发：均分功率、socTarget 取影子、失败留痕 SHED_FAILED、无 PCS 留 ALARM_ONLY。 */
class DemandShaveClientTest {

	private static EmsDemandConfig config() {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setTenantId(7L);
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("100.00"));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		return cfg;
	}

	private static PcsDevice pcs(long id, String name) {
		return new PcsDevice(id, 7L, "snd_ess_pcs", name, 3);
	}

	private static LocalDateTime win() {
		return LocalDateTime.of(2026, 8, 11, 10, 30);
	}

	@Test
	void shave_dispatchesEachPcsEqualShare() {
		CommandClient commandClient = mock(CommandClient.class);
		ShadowClient shadowClient = mock(ShadowClient.class);
		when(shadowClient.reportedSoc(1L)).thenReturn(Optional.of(60.0));
		when(shadowClient.reportedSoc(2L)).thenReturn(Optional.empty()); // 回退 30
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, shadowClient, recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(pcs(1L, "p1"), pcs(2L, "p2")), win(),
				win().plusMinutes(15), 500, 100, 200);

		verify(commandClient, times(2)).dispatch(eq("snd_ess_pcs"), anyString(), eq("DISCHARGE"), any(), eq(0L));
		verify(commandClient).dispatch(eq("snd_ess_pcs"), eq("p1"), eq("DISCHARGE"), argThat(p -> {
			return p.get("action").equals("DISCHARGE") && ((Number) p.get("power")).doubleValue() == 100.0
					&& ((Number) p.get("socTarget")).doubleValue() == 60.0;
		}), eq(0L));
		verify(commandClient).dispatch(eq("snd_ess_pcs"), eq("p2"), eq("DISCHARGE"), argThat(p -> {
			return ((Number) p.get("socTarget")).doubleValue() == 30.0;
		}), eq(0L));
		assertEquals("SHED", rec.getAction());
		assertTrue(rec.getOverLimit());
		assertEquals(0, new BigDecimal("200.00").compareTo(rec.getShavedKw()));
	}

	@Test
	void shave_noDevicesRecordsAlarmOnly() {
		CommandClient commandClient = mock(CommandClient.class);
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(), win(), win().plusMinutes(15), 500, 100, 200);

		verify(commandClient, never()).dispatch(anyString(), anyString(), anyString(), any(), anyLong());
		assertEquals("ALARM_ONLY", rec.getAction());
		assertEquals(0, new BigDecimal("0.00").compareTo(rec.getShavedKw()));
	}

	@Test
	void shave_dispatchFailureRecordsShavedFailed() {
		CommandClient commandClient = mock(CommandClient.class);
		when(commandClient.dispatch(eq("snd_ess_pcs"), eq("p1"), anyString(), any(), eq(0L)))
			.thenThrow(new RuntimeException("device offline"));
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(pcs(1L, "p1")), win(), win().plusMinutes(15), 500, 100,
				200);

		assertEquals("SHED_FAILED", rec.getAction()); // 异常被捕获，不中断
		assertEquals(0, new BigDecimal("200.00").compareTo(rec.getShavedKw())); // 保留意图削峰功率
	}

	@Test
	void shave_nullDevicesRecordsAlarmOnly() {
		CommandClient commandClient = mock(CommandClient.class);
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), null, win(), win().plusMinutes(15), 500, 100, 200);

		assertEquals("ALARM_ONLY", rec.getAction());
	}

}
