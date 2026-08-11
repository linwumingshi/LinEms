package com.energyx.ems.scheduler;

import com.energyx.common.redis.DistributedLock;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.MeterDeviceMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.service.DemandAlarmProducer;
import com.energyx.ems.service.DemandShaveClient;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.TsdbClient;
import com.energyx.ems.util.DemandDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DemandDetectScheduler 编排：超限削峰+首超告警、槽位定型防重复告警、未超限写 NONE、缺电表/遥测/限值跳过。 */
class DemandDetectSchedulerTest {

	private EmsDemandConfigService configService;

	private EmsDemandRecordService recordService;

	private MeterDeviceMapper meterDeviceMapper;

	private PcsDeviceMapper pcsDeviceMapper;

	private TsdbClient tsdbClient;

	private DemandShaveClient shaveClient;

	private DemandAlarmProducer alarmProducer;

	private DistributedLock distributedLock;

	private DemandDetectScheduler scheduler;

	@BeforeEach
	void setup() {
		configService = mock(EmsDemandConfigService.class);
		recordService = mock(EmsDemandRecordService.class);
		meterDeviceMapper = mock(MeterDeviceMapper.class);
		pcsDeviceMapper = mock(PcsDeviceMapper.class);
		tsdbClient = mock(TsdbClient.class);
		shaveClient = mock(DemandShaveClient.class);
		alarmProducer = mock(DemandAlarmProducer.class);
		distributedLock = mock(DistributedLock.class);
		when(distributedLock.runIfAcquired(anyString(), anyLong(), any(Runnable.class))).thenAnswer(inv -> {
			((Runnable) inv.getArgument(2)).run();
			return true;
		});
		scheduler = new DemandDetectScheduler(configService, recordService, meterDeviceMapper, pcsDeviceMapper,
				tsdbClient, shaveClient, alarmProducer, distributedLock);
		ReflectionTestUtils.setField(scheduler, "meterProductKey", "snd_ess_meter");
		ReflectionTestUtils.setField(scheduler, "pcsProductKey", "snd_ess_pcs");
	}

	private static EmsDemandConfig config(double limitKw) {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setTenantId(7L);
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal(limitKw));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		return cfg;
	}

	private static EmsDemandConfig config(double limitKw, long stationId) {
		EmsDemandConfig cfg = config(limitKw);
		cfg.setStationId(stationId);
		return cfg;
	}

	/** 造一条落在当前 15min 槽位内的 power 遥测（真实 DemandDetector 会算出该均值）。 */
	private static TsdbClient.TelemetryRow rowNow(double power) {
		LocalDateTime slotStart = DemandDetector.slotStart(LocalDateTime.now());
		long ts = slotStart.plusSeconds(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		return new TsdbClient.TelemetryRow(ts, power, null);
	}

	private void stubMeterAndRows(double power) {
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter"))
			.thenReturn(List.of(new MeterDevice(1L, 7L, "snd_ess_meter", "m1", 3)));
		when(tsdbClient.history(anyLong(), anyString(), any(LocalDate.class))).thenReturn(List.of(rowNow(power)));
	}

	@Test
	void detect_overLimitShavesAndPublishesAlarmOnce() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(500);
		when(pcsDeviceMapper.selectByStation(7L, 10L, "snd_ess_pcs"))
			.thenReturn(List.of(new PcsDevice(2L, 7L, "snd_ess_pcs", "p1", 3)));
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);
		when(shaveClient.shave(any(), anyList(), any(), any(), anyDouble(), anyDouble(), anyDouble()))
			.thenAnswer(inv -> {
				EmsDemandRecord r = new EmsDemandRecord();
				r.setOverLimit(true);
				r.setAction("SHED");
				return r;
			});

		scheduler.detect();

		verify(shaveClient).shave(eq(config(10)), any(), any(), any(), eq(500.0), eq(10.0), anyDouble());
		verify(alarmProducer, times(1)).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(recordService, never()).upsert(argThat(r -> !Boolean.TRUE.equals(r.getOverLimit())));
	}

	@Test
	void detect_alreadyOverSkipsAlarm() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(500);
		when(pcsDeviceMapper.selectByStation(7L, 10L, "snd_ess_pcs"))
			.thenReturn(List.of(new PcsDevice(2L, 7L, "snd_ess_pcs", "p1", 3)));
		EmsDemandRecord existing = new EmsDemandRecord();
		existing.setOverLimit(true); // 槽位已定型超限
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(existing);

		scheduler.detect();

		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(shaveClient, times(1)).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void detect_notOverUpsertsNoneRecord() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(5); // 均值 5 < 限值 10
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);

		scheduler.detect();

		verify(shaveClient, never()).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(recordService)
			.upsert(argThat(r -> !Boolean.TRUE.equals(r.getOverLimit()) && "NONE".equals(r.getAction())));
	}

	@Test
	void detect_meterMissingSkips() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter")).thenReturn(List.of());

		scheduler.detect();

		verify(tsdbClient, never()).history(anyLong(), anyString(), any());
		verify(recordService, never()).upsert(any());
	}

	@Test
	void detect_tsdbEmptySkips() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter"))
			.thenReturn(List.of(new MeterDevice(1L, 7L, "snd_ess_meter", "m1", 3)));
		when(tsdbClient.history(anyLong(), anyString(), any(LocalDate.class))).thenReturn(List.of());

		scheduler.detect();

		verify(recordService, never()).upsert(any());
		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
	}

	@Test
	void detect_noLimitSkips() {
		EmsDemandConfig cfg = config(0); // 限值 ≤ 0
		when(configService.listAll()).thenReturn(List.of(cfg));
		stubMeterAndRows(500);

		scheduler.detect();

		verify(recordService, never()).upsert(any());
		verify(shaveClient, never()).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void detect_oneStationFailureDoesNotStopLoop() {
		EmsDemandConfig bad = config(10);
		when(configService.listAll()).thenReturn(List.of(bad, config(20, 20L)));
		stubMeterAndRows(5); // 先 stub 电表/遥测，再覆写站 10 抛异常（last-stub-wins，避免重复 when() 触发上一次
								// thenThrow）
		// 第一站电表查询抛异常
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter")).thenThrow(new RuntimeException("db down"));
		// 第二站正常（站 id 20）
		when(meterDeviceMapper.selectByStation(7L, 20L, "snd_ess_meter"))
			.thenReturn(List.of(new MeterDevice(3L, 7L, "snd_ess_meter", "m3", 3)));
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);

		scheduler.detect();

		verify(recordService, times(1)).upsert(any(EmsDemandRecord.class)); // 第二站仍执行
	}

}
