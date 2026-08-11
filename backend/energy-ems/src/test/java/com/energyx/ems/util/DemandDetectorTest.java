package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DemandDetector 纯函数：15min 槽位定位 / 槽位均值 / 超限判定 / 削峰功率钳制。 */
class DemandDetectorTest {

	private static long ms(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static TsdbClient.TelemetryRow row(int minute, double power) {
		return new TsdbClient.TelemetryRow(ms(LocalDateTime.of(2026, 8, 11, 10, minute)), power, null);
	}

	@Test
	void slotStart_truncatesTo15Min() {
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 30),
				DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 37)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 30),
				DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 30)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 30),
				DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 44)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 0, 0),
				DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 0, 7)));
	}

	@Test
	void slotStart_epochMsSameAsLocalDateTime() {
		LocalDateTime t = LocalDateTime.of(2026, 8, 11, 10, 37);
		assertEquals(DemandDetector.slotStart(t), DemandDetector.slotStart(ms(t)));
	}

	@Test
	void slotEnd_is15MinutesLater() {
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 45),
				DemandDetector.slotEnd(LocalDateTime.of(2026, 8, 11, 10, 30)));
	}

	@Test
	void slotAvg_averagesRowsInsideSlot() {
		LocalDateTime start = LocalDateTime.of(2026, 8, 11, 10, 30);
		List<TsdbClient.TelemetryRow> rows = List.of(row(30, 100), row(32, 120), row(40, 140));
		assertEquals(120.0, DemandDetector.slotAvg(rows, start));
	}

	@Test
	void slotAvg_excludesOutOfSlotAndNullPower() {
		LocalDateTime start = LocalDateTime.of(2026, 8, 11, 10, 30);
		List<TsdbClient.TelemetryRow> rows = List.of(row(29, 1000), // 槽位外（10:30 前）
				row(45, 1000), // 槽位外（10:45 起）
				row(31, 200), new TsdbClient.TelemetryRow(ms(LocalDateTime.of(2026, 8, 11, 10, 33)), null, null), // 空功率跳过
				row(35, 400));
		assertEquals(300.0, DemandDetector.slotAvg(rows, start));
	}

	@Test
	void slotAvg_emptyReturnsZero() {
		assertEquals(0.0, DemandDetector.slotAvg(List.of(), LocalDateTime.of(2026, 8, 11, 10, 30)));
	}

	@Test
	void detect_overWithPcsShedsClampedToAvailable() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 100, 2); // 需 400，可用
																			// 2×100=200
		assertTrue(r.overLimit());
		assertEquals(200.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.SHED, r.action());
	}

	@Test
	void detect_overWithPcsShaveLessThanNeeded() {
		DemandDetector.DetectResult r = DemandDetector.detect(150, 100, 2); // 需 50 < 可用
																			// 200
		assertTrue(r.overLimit());
		assertEquals(50.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.SHED, r.action());
	}

	@Test
	void detect_noPcsAlarmOnly() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 100, 0);
		assertTrue(r.overLimit());
		assertEquals(0.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.ALARM_ONLY, r.action());
	}

	@Test
	void detect_notOverNone() {
		DemandDetector.DetectResult r = DemandDetector.detect(80, 100, 2);
		assertFalse(r.overLimit());
		assertEquals(0.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

	@Test
	void detect_atLimitNotOver() {
		// 严格大于才判超限
		DemandDetector.DetectResult r = DemandDetector.detect(100, 100, 2);
		assertFalse(r.overLimit());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

	@Test
	void detect_nonPositiveLimitNeverOver() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 0, 2);
		assertFalse(r.overLimit());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

}
