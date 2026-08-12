package com.energyx.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** alarm/product/station 模块业务枚举统一测试：code/desc/fromCode/of 语义与 DB 存量值一致（P2 枚举化 Task 3） */
class AlarmProductEnumsTest {

	@Test
	void alarmLevel_codesAndLookup() {
		assertEquals(1, AlarmLevel.PROMPT.getCode());
		assertEquals(2, AlarmLevel.GENERAL.getCode());
		assertEquals(3, AlarmLevel.SERIOUS.getCode());
		assertEquals(4, AlarmLevel.CRITICAL.getCode());
		assertEquals("严重", AlarmLevel.SERIOUS.getDesc());
		assertEquals(AlarmLevel.CRITICAL, AlarmLevel.fromCode(4));
		assertEquals(AlarmLevel.GENERAL, AlarmLevel.of(2));
		assertNull(AlarmLevel.of(0));
		assertNull(AlarmLevel.of(null));
		assertThrows(IllegalArgumentException.class, () -> AlarmLevel.fromCode(9));
	}

	@Test
	void alarmRecordStatus_codesAndLookup() {
		assertEquals(0, AlarmRecordStatus.ACTIVE.getCode());
		assertEquals(1, AlarmRecordStatus.RECOVERED.getCode());
		assertEquals(2, AlarmRecordStatus.ACKED.getCode());
		assertEquals("触发中", AlarmRecordStatus.ACTIVE.getDesc());
		assertEquals("已确认", AlarmRecordStatus.ACKED.getDesc());
		assertEquals(AlarmRecordStatus.RECOVERED, AlarmRecordStatus.fromCode(1));
		assertEquals(AlarmRecordStatus.ACKED, AlarmRecordStatus.of(2));
		assertNull(AlarmRecordStatus.of(9));
	}

	@Test
	void alarmRuleStatus_codesAndLookup() {
		assertEquals(0, AlarmRuleStatus.DISABLED.getCode());
		assertEquals(1, AlarmRuleStatus.ENABLED.getCode());
		assertEquals("停用", AlarmRuleStatus.DISABLED.getDesc());
		assertEquals(AlarmRuleStatus.ENABLED, AlarmRuleStatus.fromCode(1));
		assertEquals(AlarmRuleStatus.DISABLED, AlarmRuleStatus.of(0));
		assertNull(AlarmRuleStatus.of(null));
	}

	@Test
	void productStatus_codesAndLookup() {
		assertEquals(0, ProductStatus.DISABLED.getCode());
		assertEquals(1, ProductStatus.ENABLED.getCode());
		assertEquals("禁用", ProductStatus.DISABLED.getDesc());
		assertEquals(ProductStatus.ENABLED, ProductStatus.fromCode(1));
		assertEquals(ProductStatus.DISABLED, ProductStatus.of(0));
		assertNull(ProductStatus.of(3));
	}

	@Test
	void thingModelStatus_codesAndLookup() {
		assertEquals(0, ThingModelStatus.DRAFT.getCode());
		assertEquals(1, ThingModelStatus.PUBLISHED.getCode());
		assertEquals(2, ThingModelStatus.DEPRECATED.getCode());
		assertEquals("已发布", ThingModelStatus.PUBLISHED.getDesc());
		assertEquals(ThingModelStatus.PUBLISHED, ThingModelStatus.fromCode(1));
		assertEquals(ThingModelStatus.DEPRECATED, ThingModelStatus.of(2));
		assertNull(ThingModelStatus.of(5));
	}

	@Test
	void stationStatus_codesAndLookup() {
		assertEquals(0, StationStatus.STOPPED.getCode());
		assertEquals(1, StationStatus.RUNNING.getCode());
		assertEquals("停运", StationStatus.STOPPED.getDesc());
		assertEquals("运行", StationStatus.RUNNING.getDesc());
		assertEquals(StationStatus.RUNNING, StationStatus.fromCode(1));
		assertEquals(StationStatus.STOPPED, StationStatus.of(0));
		assertNull(StationStatus.of(null));
	}

	@Test
	void gridType_codesAndLookup() {
		assertEquals("工商业", GridType.COMMERCIAL_INDUSTRIAL.getCode());
		assertEquals("园区", GridType.PARK.getCode());
		assertEquals("电网侧", GridType.GRID.getCode());
		assertEquals("工商业", GridType.COMMERCIAL_INDUSTRIAL.getDesc());
		assertEquals(GridType.PARK, GridType.fromCode("园区"));
		assertEquals(GridType.GRID, GridType.of("电网侧"));
		// 存量 DDL 默认值 'INDUSTRIAL' 兼容映射 → 工商业
		assertEquals(GridType.COMMERCIAL_INDUSTRIAL, GridType.of("INDUSTRIAL"));
		assertEquals(GridType.COMMERCIAL_INDUSTRIAL, GridType.fromCode("INDUSTRIAL"));
		assertNull(GridType.of(null));
	}

}
