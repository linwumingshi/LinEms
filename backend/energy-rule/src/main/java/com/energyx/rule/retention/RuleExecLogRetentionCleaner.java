package com.energyx.rule.retention;

import com.energyx.common.redis.DistributedLock;
import com.energyx.common.retention.DataRetention;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 规则执行日志保留清理（D-02）：定时删除超过保留期的 iot_scene_exec_log，防磁盘无限增长。
 *
 * <p>
 * 与 alarm/ems 等模块 RetentionCleaner 同模式：xxl-job 触发 + DistributedLock 防多实例重复执行；
 * 按月分区表按行批删兜底（生产可改为 DROP 旧分区，见 DDL 注释）。
 * </p>
 */
@Slf4j
@Component
public class RuleExecLogRetentionCleaner {

	/** 分布式锁 key（最终 Redis key：lock:retention:scene-exec-log） */
	private static final String LOCK_KEY = "retention:scene-exec-log";

	/** 保留天数（对齐设计 §5：执行日志保留 30 天） */
	private static final int KEEP_DAYS = 30;

	private final JdbcTemplate jdbc;

	private final DistributedLock distributedLock;

	public RuleExecLogRetentionCleaner(JdbcTemplate jdbc, DistributedLock distributedLock) {
		this.jdbc = jdbc;
		this.distributedLock = distributedLock;
	}

	/** 每日 04:00 清理 30 天前的 iot_scene_exec_log（xxl-job 触发，admin cron=0 0 4 * * *） */
	@XxlJob("ruleExecLogRetentionClean")
	public void scheduledClean() {
		distributedLock.runIfAcquired(LOCK_KEY, 600, () -> {
			int deleted = DataRetention.cleanByTime(jdbc, "iot_scene_exec_log", "create_time",
					LocalDateTime.now().minusDays(KEEP_DAYS), 500);
			if (deleted > 0) {
				log.info("[Retention] 清理 iot_scene_exec_log {} 条", deleted);
			}
		});
	}

}
