package com.energyx.common.retention;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 数据保留通用批删（D-02：历史数据滚动清理，防磁盘无限增长）。
 *
 * <p>
 * 用法：各模块 @Scheduled 定时任务 + {@code DistributedLock} 防多实例重复清理，
 * 对本模块的增长表按时间列循环批删（{@code DELETE ... WHERE timeCol < cutoff LIMIT batch}， 每次删 batch
 * 行直到删不满一批，避免一次性大事务锁表）。表名/时间列由调用方白名单硬编码， 不接收外部输入，无注入风险。
 * </p>
 */
public final class DataRetention {

	private DataRetention() {
	}

	/**
	 * 按时间列批删过期数据（循环至单批删不满 batchSize 为止）。
	 * @param jdbc 模块 JdbcTemplate（模块有 DataSource 即自动装配）
	 * @param table 白名单表名（调用方硬编码）
	 * @param timeColumn 时间列（create_time/triggered_time 等）
	 * @param cutoff 保留截止时间（早于此时间的记录删除）
	 * @param batchSize 单批删除行数上限（默认 500）
	 * @return 总删除行数
	 */
	public static int cleanByTime(JdbcTemplate jdbc, String table, String timeColumn, LocalDateTime cutoff,
			int batchSize) {
		Timestamp threshold = Timestamp.valueOf(cutoff);
		int total = 0;
		int deleted;
		do {
			deleted = jdbc.update(
					"DELETE FROM " + table + " WHERE " + timeColumn + " < ? LIMIT " + Math.max(1, batchSize),
					threshold);
			total += deleted;
		}
		while (deleted >= batchSize);
		return total;
	}

}
