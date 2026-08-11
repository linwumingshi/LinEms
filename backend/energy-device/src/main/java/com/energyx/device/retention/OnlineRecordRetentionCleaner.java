package com.energyx.device.retention;

import com.energyx.common.redis.DistributedLock;
import com.energyx.common.retention.DataRetention;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据保留清理（D-02）：定时删除超过保留期的历史数据，防磁盘无限增长。 R-01 分布式锁保证多实例仅一个执行清理。
 */
@Slf4j
@Component
public class OnlineRecordRetentionCleaner {

	/** 分布式锁 key（最终 Redis key：lock:retention:online-record） */
	private static final String LOCK_KEY = "retention:online-record";

	/** 保留天数 */
	private static final int KEEP_DAYS = 30;

	private final JdbcTemplate jdbc;

	private final DistributedLock distributedLock;

	public OnlineRecordRetentionCleaner(JdbcTemplate jdbc, DistributedLock distributedLock) {
		this.jdbc = jdbc;
		this.distributedLock = distributedLock;
	}

	/**
	 * 每日 03:30 清理 30 天前的 iot_device_online_record 数据（xxl-job 触发，admin cron=0 30 3 * * *）
	 */
	@XxlJob("deviceOnlineRetentionClean")
	public void scheduledClean() {
		distributedLock.runIfAcquired(LOCK_KEY, 600, () -> {
			int deleted = DataRetention.cleanByTime(jdbc, "iot_device_online_record", "create_time",
					LocalDateTime.now().minusDays(KEEP_DAYS), 500);
			if (deleted > 0) {
				log.info("[Retention] 清理 iot_device_online_record {} 条", deleted);
			}
		});
	}

}
