package com.energyx.tsdb.sql;

import com.energyx.common.redis.DistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 空闲兜底冲刷：低流量时行数达不到阈值，定时把缓冲存量行写入 TDengine， 控制摄取端到端延迟上限（≤1s）。写入失败仅记日志，行保留待重试。
 *
 * <p>
 * R-01 分布式锁：多实例部署时仅一个实例冲刷缓冲，防同一批行被重复写入（TDengine 覆盖写可兜底，但避免无谓重复）。
 * </p>
 */
@Slf4j
@Component
public class TsdbFlushScheduler {

	/** 分布式锁 key（最终 Redis key：lock:scheduled:tsdb-flush，见 Redis-key 规范） */
	private static final String LOCK_KEY = "scheduled:tsdb-flush";

	/** 锁 TTL（秒）：冲刷为毫秒级操作，30s 充分覆盖最坏耗时 */
	private static final long LOCK_TTL_SECONDS = 30;

	private final TsdbBatchBuffer buffer;

	private final DistributedLock distributedLock;

	public TsdbFlushScheduler(TsdbBatchBuffer buffer, DistributedLock distributedLock) {
		this.buffer = buffer;
		this.distributedLock = distributedLock;
	}

	@Scheduled(fixedDelay = 1000)
	public void flushIdle() {
		distributedLock.runIfAcquired(LOCK_KEY, LOCK_TTL_SECONDS, this::doFlushIdle);
	}

	private void doFlushIdle() {
		try {
			int n = buffer.flush();
			if (n > 0) {
				log.debug("[Tsdb] 空闲冲刷 {} 行", n);
			}
		}
		catch (Exception e) {
			log.error("[Tsdb] 空闲冲刷失败（行已保留待重试）", e);
		}
	}

}
