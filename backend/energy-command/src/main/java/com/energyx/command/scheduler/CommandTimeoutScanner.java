package com.energyx.command.scheduler;

import com.energyx.command.service.CommandService;
import com.energyx.common.redis.DistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ACK 超时扫描（@Scheduled，间隔可配）。
 *
 * <p>
 * 扫描 iot_command 在途状态（SENT/RECEIVED/EXECUTING）且 sent_time 超时（idx_cmd_state_time）： 重试未耗尽 →
 * 在线重发/离线重新入队；重试耗尽 → 置 TIMEOUT 终态。
 * </p>
 *
 * <p>
 * R-01 分布式锁：多实例部署时仅一个实例执行扫描，防止指令重复超时重发（锁 TTL 60s 覆盖扫描最坏耗时）。
 * </p>
 */
@Slf4j
@Component
public class CommandTimeoutScanner {

	/** 分布式锁 key（最终 Redis key：lock:scheduled:command-scan，见 Redis-key 规范） */
	private static final String LOCK_KEY = "scheduled:command-scan";

	/** 锁 TTL（秒）：大于扫描最坏耗时，到期自动释放防持有者宕机死锁 */
	private static final long LOCK_TTL_SECONDS = 60;

	private final CommandService commandService;

	private final DistributedLock distributedLock;

	public CommandTimeoutScanner(CommandService commandService, DistributedLock distributedLock) {
		this.commandService = commandService;
		this.distributedLock = distributedLock;
	}

	@Scheduled(fixedDelayString = "${energyx.command.scan-interval-ms:5000}",
			initialDelayString = "${energyx.command.scan-initial-delay-ms:10000}")
	public void scan() {
		distributedLock.runIfAcquired(LOCK_KEY, LOCK_TTL_SECONDS, this::doScan);
	}

	private void doScan() {
		try {
			commandService.timeoutScan();
		}
		catch (Exception e) {
			log.error("[Command] 超时扫描异常", e);
		}
	}

}
