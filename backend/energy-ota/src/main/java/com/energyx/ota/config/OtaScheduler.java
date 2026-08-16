package com.energyx.ota.config;

import com.energyx.common.redis.DistributedLock;
import com.energyx.ota.service.OtaNotifyService;
import com.energyx.ota.service.OtaTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OTA 运维调度器（S4-2，@Scheduled + DistributedLock 防多实例重复执行）。
 *
 * <ul>
 * <li>超时扫描 1min：下载/升级超时设备重试或置 TIMEOUT；</li>
 * <li>重试扫描 1min：失败且 retry_at 已到且未耗尽 → 重新下发；</li>
 * <li>灰度推进 5min：执行中灰度任务按成功率推进/自动暂停（S4-1）。</li>
 * </ul>
 */
@Slf4j
@Component
public class OtaScheduler {

	private static final String LOCK_TIMEOUT_SCAN = "ota:lock:timeout-scan";

	private static final String LOCK_RETRY_SCAN = "ota:lock:retry-scan";

	private static final String LOCK_GRAY_ADVANCE = "ota:lock:gray-advance";

	private final OtaTaskService taskService;

	private final OtaNotifyService notifyService;

	private final DistributedLock distributedLock;

	public OtaScheduler(OtaTaskService taskService, OtaNotifyService notifyService, DistributedLock distributedLock) {
		this.taskService = taskService;
		this.notifyService = notifyService;
		this.distributedLock = distributedLock;
	}

	/** 超时扫描（每分钟；锁 110s 防重叠） */
	@Scheduled(cron = "0 * * * * ?")
	public void timeoutScan() {
		distributedLock.runIfAcquired(LOCK_TIMEOUT_SCAN, 110, () -> {
			try {
				taskService.scanTimeout();
			}
			catch (Exception e) {
				log.error("[OTA] 超时扫描异常", e);
			}
		});
	}

	/** 重试扫描（每分钟；锁 110s 防重叠） */
	@Scheduled(cron = "30 * * * * ?")
	public void retryScan() {
		distributedLock.runIfAcquired(LOCK_RETRY_SCAN, 110, () -> {
			try {
				taskService.scanRetry();
			}
			catch (Exception e) {
				log.error("[OTA] 重试扫描异常", e);
			}
		});
	}

	/** 灰度推进（每 5 分钟；锁 290s 防重叠） */
	@Scheduled(cron = "0 */5 * * * ?")
	public void grayAdvanceScan() {
		distributedLock.runIfAcquired(LOCK_GRAY_ADVANCE, 290, () -> {
			try {
				taskService.scanGrayAdvance();
			}
			catch (Exception e) {
				log.error("[OTA] 灰度推进扫描异常", e);
			}
		});
	}

}
