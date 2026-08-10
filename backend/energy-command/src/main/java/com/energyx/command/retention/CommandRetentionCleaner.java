package com.energyx.command.retention;

import com.energyx.common.redis.DistributedLock;
import com.energyx.common.retention.DataRetention;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据保留清理（D-02）：定时删除超过保留期的历史数据，防磁盘无限增长。 R-01 分布式锁保证多实例仅一个执行清理。
 */
@Slf4j
@Component
public class CommandRetentionCleaner {

	/** 分布式锁 key（最终 Redis key：lock:retention:command） */
	private static final String LOCK_KEY = "retention:command";

	/** 保留天数 */
	private static final int KEEP_DAYS = 90;

	private final JdbcTemplate jdbc;

	private final DistributedLock distributedLock;

	public CommandRetentionCleaner(JdbcTemplate jdbc, DistributedLock distributedLock) {
		this.jdbc = jdbc;
		this.distributedLock = distributedLock;
	}

	/** 每日 03:30 清理 90 天前的 iot_command 数据 */
	@Scheduled(cron = "0 30 3 * * *")
	public void scheduledClean() {
		distributedLock.runIfAcquired(LOCK_KEY, 600, () -> {
			int deleted = DataRetention.cleanByTime(jdbc, "iot_command", "create_time",
					LocalDateTime.now().minusDays(KEEP_DAYS), 500);
			if (deleted > 0) {
				log.info("[Retention] 清理 iot_command {} 条", deleted);
			}
		});
	}

}
