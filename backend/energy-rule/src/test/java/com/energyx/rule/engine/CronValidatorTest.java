package com.energyx.rule.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6 位 cron 表达式语法校验测试（合法/非法/边界）。
 */
class CronValidatorTest {

	@Test
	@DisplayName("合法：完整 6 位标准 cron")
	void validCrons() {
		assertTrue(CronValidator.isValid("0 30 22 * * ?"));
		assertTrue(CronValidator.isValid("0 0 22 * * 5"));
		assertTrue(CronValidator.isValid("0 */5 * * * ?"));
		assertTrue(CronValidator.isValid("30 15 8 1 1 ?"));
		assertTrue(CronValidator.isValid("0 0 0 ? * MON-FRI"));
		assertTrue(CronValidator.isValid("0 0 12 1/5 * ?"));
	}

	@Test
	@DisplayName("非法：位数不足/超范围/非法字符/空值")
	void invalidCrons() {
		assertFalse(CronValidator.isValid(null));
		assertFalse(CronValidator.isValid(""));
		assertFalse(CronValidator.isValid("   "));
		// 位数不足
		assertFalse(CronValidator.isValid("0 30 22 * *"));
		// 秒超范围
		assertFalse(CronValidator.isValid("60 30 22 * * ?"));
		// 时超范围
		assertFalse(CronValidator.isValid("0 30 24 * * ?"));
		// 月超范围
		assertFalse(CronValidator.isValid("0 30 22 * 13 ?"));
		// 非法字符
		assertFalse(CronValidator.isValid("0 30 22 * * @"));
		// 字母（非 ? 或 MON 缩写不支持的段）
		assertFalse(CronValidator.isValid("0 30 22 * * ABC"));
	}

	@Test
	@DisplayName("边界：0 点和 59 分 59 秒等合法极值")
	void boundaryCrons() {
		assertTrue(CronValidator.isValid("59 59 23 31 12 7"));
		assertTrue(CronValidator.isValid("0 0 0 1 1 0"));
		assertTrue(CronValidator.isValid("0 0 23 31 12 ?"));
	}

}
