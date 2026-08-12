package com.energyx.tsdb.sql;

import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.writer.TsdbWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量缓冲测试：阈值冲刷、失败回滚保序、空缓冲不执行。
 */
class TsdbBatchBufferTest {

	@Test
	void add_shouldFlushWhenReachingBatchSize() throws Exception {
		List<String> executed = new ArrayList<>();
		TsdbWriter fake = executed::add;
		TsdbProperties props = new TsdbProperties();
		props.setBatchSize(3);
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(fake, props);

		buffer.add("r1");
		buffer.add("r2");
		assertEquals(2, buffer.pending());

		buffer.add("r3"); // 触发阈值冲刷
		assertEquals(0, buffer.pending());
		assertEquals(1, executed.size());
		assertEquals("r1 r2 r3", executed.get(0)); // 非 INSERT 前缀原样空格拼接
	}

	@Test
	void add_failure_shouldRollbackRowsInOrder() throws Exception {
		TsdbWriter failing = sql -> {
			throw new IllegalStateException("tdengine down");
		};
		TsdbProperties props = new TsdbProperties();
		props.setBatchSize(2);
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(failing, props);

		buffer.add("r1"); // 未达阈值，不冲刷
		assertEquals(1, buffer.pending());

		assertThrows(Exception.class, () -> buffer.add("r2")); // 触发冲刷 → 失败上抛
		assertEquals(2, buffer.pending()); // r1、r2 已回滚

		assertThrows(Exception.class, buffer::flush);
		assertEquals(2, buffer.pending());
	}

	@Test
	void flush_empty_shouldReturnZeroWithoutCallingWriter() throws Exception {
		TsdbWriter exploding = sql -> {
			throw new AssertionError("空缓冲不应触发写入");
		};
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(exploding, new TsdbProperties());
		assertEquals(0, buffer.flush());
		assertEquals(0, buffer.pending());
	}

	@Test
	void flush_shouldReturnWrittenRowCount() throws Exception {
		List<String> executed = new ArrayList<>();
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(executed::add, new TsdbProperties());
		buffer.add("r1");
		buffer.add("r2");
		assertEquals(2, buffer.flush()); // flush 返回写入行数
		assertEquals(1, executed.size()); // 两行合成一条语句
		assertEquals("r1 r2", executed.get(0));
		assertEquals(0, buffer.pending());
	}

	@Test
	void flush_failure_shouldRollbackThenRecover() throws Exception {
		List<String> executed = new ArrayList<>();
		int[] calls = { 0 };
		TsdbWriter flaky = sql -> {
			if (calls[0]++ == 0) {
				throw new IllegalStateException("transient");
			}
			executed.add(sql);
		};
		TsdbProperties props = new TsdbProperties();
		props.setBatchSize(2);
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(flaky, props);

		assertThrows(Exception.class, () -> {
			buffer.add("r1");
			buffer.add("r2");
		});
		assertEquals(2, buffer.pending());

		buffer.flush(); // 第二次成功
		assertEquals(0, buffer.pending());
		assertEquals(1, executed.size());
		assertEquals("r1 r2", executed.get(0));
	}

	@Test
	void joinBatch_shouldStripInsertPrefixOnSubsequentBlocks() throws Exception {
		// TDengine 批量语法：仅首个块保留 INSERT INTO，后续子表块空格直接接表名
		List<String> executed = new ArrayList<>();
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(executed::add, new TsdbProperties());
		buffer.add("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_x TAGS ('1') (ts) VALUES (1)");
		buffer.add("INSERT INTO iot_tsdb_raw.dev_2 USING iot_tsdb_raw.st_prop_x TAGS ('2') (ts) VALUES (2)");
		buffer.add("INSERT INTO iot_tsdb_raw.dev_3 USING iot_tsdb_raw.st_prop_x TAGS ('3') (ts) VALUES (3)");
		assertEquals(3, buffer.flush());

		String joined = executed.get(0);
		assertEquals(1, joined.split("INSERT INTO").length - 1, "仅首个块保留 INSERT INTO");
		assertTrue(joined.startsWith("INSERT INTO iot_tsdb_raw.dev_1"), joined);
		assertTrue(joined.contains(" iot_tsdb_raw.dev_2 USING"), joined);
		assertTrue(joined.contains(" iot_tsdb_raw.dev_3 USING"), joined);
	}

	@Test
	void joinBatch_singleRow_shouldReturnAsIs() throws Exception {
		List<String> executed = new ArrayList<>();
		TsdbBatchBuffer buffer = new TsdbBatchBuffer(executed::add, new TsdbProperties());
		buffer.add("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_x TAGS ('1') (ts) VALUES (1)");
		assertEquals(1, buffer.flush());
		assertEquals("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_x TAGS ('1') (ts) VALUES (1)",
				executed.get(0));
	}

}
