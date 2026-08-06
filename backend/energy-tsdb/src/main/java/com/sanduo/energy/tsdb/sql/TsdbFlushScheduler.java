package com.sanduo.energy.tsdb.sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 空闲兜底冲刷：低流量时行数达不到阈值，定时把缓冲存量行写入 TDengine，
 * 控制摄取端到端延迟上限（≤1s）。写入失败仅记日志，行保留待重试。
 */
@Slf4j
@Component
public class TsdbFlushScheduler {

    private final TsdbBatchBuffer buffer;

    public TsdbFlushScheduler(TsdbBatchBuffer buffer) {
        this.buffer = buffer;
    }

    @Scheduled(fixedDelay = 1000)
    public void flushIdle() {
        try {
            int n = buffer.flush();
            if (n > 0) {
                log.debug("[Tsdb] 空闲冲刷 {} 行", n);
            }
        } catch (Exception e) {
            log.error("[Tsdb] 空闲冲刷失败（行已保留待重试）", e);
        }
    }
}
