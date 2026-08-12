package com.energyx.tsdb.sql;

import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.writer.TsdbWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * TDengine 批量缓冲：多行 INSERT SQL 聚合成一条语句一次 execute （TDengine 支持一语句多 INSERT 块），降低网络
 * round-trip 提升吞吐。
 *
 * <p>
 * 行为：
 * <ul>
 * <li>达到行数/字节阈值立即冲刷（消费线程内，天然背压）；</li>
 * <li>冲刷失败把已取出行<b>回滚到队列头</b>（保序）并上抛 → 当前记录进 DLQ， 缓冲里的存量行在下次 add/flush 时重试，不丢失；</li>
 * <li>idle 时由 {@link TsdbFlushScheduler} 每 1s 兜底冲刷，避免低流量下滞留。</li>
 * </ul>
 * 线程安全：add/flush 全 synchronized，消费线程与调度线程可并发调用。
 */
@Slf4j
@Component
public class TsdbBatchBuffer {

	private final TsdbWriter writer;

	private final TsdbProperties props;

	private final Deque<String> rows = new ArrayDeque<>();

	private long estBytes;

	public TsdbBatchBuffer(TsdbWriter writer, TsdbProperties props) {
		this.writer = writer;
		this.props = props;
	}

	/** 追加一行；达到阈值时同步冲刷（失败上抛，触发调用方 DLQ 语义） */
	public synchronized void add(String sql) throws Exception {
		rows.addLast(sql);
		estBytes += sql.length();
		if (rows.size() >= props.getBatchSize() || estBytes >= props.getBatchBytes()) {
			flushNow();
		}
	}

	/** 冲刷全部待写行，返回写入行数；无数据返回 0。失败上抛（行已回滚）。 */
	public synchronized int flush() throws Exception {
		int count = rows.size();
		if (count == 0) {
			return 0;
		}
		flushNow();
		return count;
	}

	/** 当前待写行数（监控用） */
	public synchronized int pending() {
		return rows.size();
	}

	private void flushNow() throws Exception {
		List<String> batch = new ArrayList<>(rows);
		rows.clear();
		estBytes = 0;
		try {
			writer.execute(joinBatch(batch));
			log.debug("[Tsdb] 批量写入 {} 行", batch.size());
		}
		catch (Exception e) {
			// 回滚：失败行放回队头，保序重试；避免消费偏移已提交但数据丢失
			for (int i = batch.size() - 1; i >= 0; i--) {
				rows.addFirst(batch.get(i));
				estBytes += batch.get(i).length();
			}
			throw e;
		}
	}

	/**
	 * 拼接 TDengine 批量写入语句：一条 INSERT 语句内多子表块，仅首个块保留 INSERT INTO 前缀， 后续块以空格直接接子表名（TDengine
	 * 语法：{@code INSERT INTO t1 ... VALUES(...) t2 ... VALUES(...)}）。 单行（首块）直接返回原样，避免无谓解析。
	 */
	private static String joinBatch(List<String> batch) {
		if (batch.size() <= 1) {
			return batch.isEmpty() ? "" : batch.get(0);
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < batch.size(); i++) {
			String sql = batch.get(i);
			if (i == 0) {
				sb.append(sql);
			}
			else {
				// 去掉行首 "INSERT INTO "（14 字符），保留子表块后续内容；非本前缀的 SQL 原样保留
				sb.append(' ');
				sb.append(sql.startsWith("INSERT INTO ") ? sql.substring("INSERT INTO ".length()) : sql);
			}
		}
		return sb.toString();
	}

}
