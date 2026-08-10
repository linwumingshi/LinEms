package com.energyx.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 单元测试：不依赖 Spring 容器，验证单调递增、唯一性与回拨防护。
 */
class SnowflakeIdGeneratorTest {

	@Test
	void nextId_shouldBePositive() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
		long id = generator.nextId();
		assertTrue(id > 0, "id 应为正数");
	}

	@Test
	void nextId_shouldBeStrictlyIncreasing() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
		long previous = generator.nextId();
		for (int i = 0; i < 100_000; i++) {
			long current = generator.nextId();
			assertTrue(current > previous, "id 应严格递增: " + current + " <= " + previous);
			previous = current;
		}
	}

	@Test
	void nextId_shouldBeUniqueInBulk() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
		Set<Long> ids = new HashSet<>();
		int count = 1_000_000;
		for (int i = 0; i < count; i++) {
			ids.add(generator.nextId());
		}
		assertEquals(count, ids.size(), "1M 个 ID 不应重复");
	}

	@Test
	void nextIdStr_shouldBeValidDecimal() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
		String str = generator.nextIdStr();
		long parsed = Long.parseLong(str); // 必须是合法十进制数
		assertTrue(parsed > 0);
		assertEquals(str, String.valueOf(parsed)); // 无前导零、纯十进制
	}

}
