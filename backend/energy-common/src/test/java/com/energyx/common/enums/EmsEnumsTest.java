package com.energyx.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ems 业务枚举统一测试：code/desc/fromCode/of 语义与 DB 存量值一致（P2 枚举化 Task 2） */
class EmsEnumsTest {

	@Test
	void strategyStatus_codesAndLookup() {
		assertEquals(0, StrategyStatus.DRAFT.getCode());
		assertEquals(1, StrategyStatus.ENABLED.getCode());
		assertEquals(2, StrategyStatus.DISABLED.getCode());
		assertEquals("草稿", StrategyStatus.DRAFT.getDesc());
		assertEquals(StrategyStatus.ENABLED, StrategyStatus.fromCode(1));
		assertEquals(StrategyStatus.DISABLED, StrategyStatus.of(2));
		assertNull(StrategyStatus.of(99));
		assertNull(StrategyStatus.of(null));
		assertThrows(IllegalArgumentException.class, () -> StrategyStatus.fromCode(9));
	}

	@Test
	void planStatus_codesAndLookup() {
		assertEquals(0, PlanStatus.PENDING.getCode());
		assertEquals(1, PlanStatus.RUNNING.getCode());
		assertEquals(2, PlanStatus.COMPLETED.getCode());
		assertEquals(3, PlanStatus.CANCELED.getCode());
		assertEquals(4, PlanStatus.FAILED.getCode()); // 计划执行失败态（实现补充，见 EmsPlan.status
														// 使用处）
		assertEquals("执行中", PlanStatus.RUNNING.getDesc());
		assertEquals(PlanStatus.COMPLETED, PlanStatus.of(2));
		assertEquals(PlanStatus.FAILED, PlanStatus.of(4));
		assertNull(PlanStatus.of(-1));
	}

	@Test
	void planPointState_codesAndLookup() {
		assertEquals(0, PlanPointState.PENDING.getCode());
		assertEquals(1, PlanPointState.DISPATCHED.getCode());
		assertEquals(2, PlanPointState.SUCCESS.getCode());
		assertEquals(3, PlanPointState.FAILED.getCode());
		assertEquals(4, PlanPointState.TIMEOUT.getCode());
		assertEquals(PlanPointState.SUCCESS, PlanPointState.fromCode(2));
		assertEquals(PlanPointState.DISPATCHED, PlanPointState.of(1));
		assertNull(PlanPointState.of(9));
	}

	@Test
	void electricityPriceStatus_codesAndLookup() {
		assertEquals(0, ElectricityPriceStatus.DISABLED.getCode());
		assertEquals(1, ElectricityPriceStatus.ENABLED.getCode());
		assertEquals(ElectricityPriceStatus.ENABLED, ElectricityPriceStatus.fromCode(1));
		assertNull(ElectricityPriceStatus.of(null));
	}

	@Test
	void constraintStatus_codesAndLookup() {
		assertEquals(0, ConstraintStatus.DISABLED.getCode());
		assertEquals(1, ConstraintStatus.ENABLED.getCode());
		assertEquals(ConstraintStatus.ENABLED, ConstraintStatus.of(1));
	}

	@Test
	void priceType_codesAndLookup() {
		assertEquals("DEEP", PriceType.DEEP.getCode());
		assertEquals("PEEK", PriceType.PEEK.getCode());
		assertEquals("尖峰", PriceType.PEEK.getDesc());
		assertEquals(PriceType.VALLEY, PriceType.fromCode("VALLEY"));
		assertEquals(PriceType.PEAK, PriceType.of("PEAK"));
		assertNull(PriceType.of("NONE"));
		assertThrows(IllegalArgumentException.class, () -> PriceType.fromCode("X"));
	}

	@Test
	void strategyType_codesAndGeneratable() {
		assertEquals("PEAK_VALLEY", StrategyType.PEAK_VALLEY.getCode());
		assertEquals("SOC_CTRL", StrategyType.SOC_CTRL.getCode());
		// 可生成计划的策略：峰谷/需量/时间（与前端 STRATEGY_GENERATABLE_TYPES 对齐）
		assertTrue(StrategyType.PEAK_VALLEY.isGeneratable());
		assertTrue(StrategyType.DEMAND.isGeneratable());
		assertTrue(StrategyType.TIME.isGeneratable());
		assertFalse(StrategyType.DR.isGeneratable());
		assertFalse(StrategyType.SOC_CTRL.isGeneratable());
		assertEquals(StrategyType.DEMAND, StrategyType.fromCode("DEMAND"));
		assertNull(StrategyType.of(null));
	}

	@Test
	void revenuePeriodType_codesAndLookup() {
		assertEquals("DAY", RevenuePeriodType.DAY.getCode());
		assertEquals("MONTH", RevenuePeriodType.MONTH.getCode());
		assertEquals("YEAR", RevenuePeriodType.YEAR.getCode());
		assertEquals(RevenuePeriodType.MONTH, RevenuePeriodType.fromCode("MONTH"));
		assertEquals(RevenuePeriodType.DAY, RevenuePeriodType.of("DAY"));
		assertNull(RevenuePeriodType.of("WEEK"));
	}

}
