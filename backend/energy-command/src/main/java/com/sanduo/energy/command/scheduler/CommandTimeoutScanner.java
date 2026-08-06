package com.sanduo.energy.command.scheduler;

import com.sanduo.energy.command.service.CommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ACK 超时扫描（@Scheduled，间隔可配）。
 *
 * <p>扫描 iot_command 在途状态（SENT/RECEIVED/EXECUTING）且 sent_time 超时（idx_cmd_state_time）：
 * 重试未耗尽 → 在线重发/离线重新入队；重试耗尽 → 置 TIMEOUT 终态。</p>
 */
@Slf4j
@Component
public class CommandTimeoutScanner {

    private final CommandService commandService;

    public CommandTimeoutScanner(CommandService commandService) {
        this.commandService = commandService;
    }

    @Scheduled(fixedDelayString = "${sanduo.command.scan-interval-ms:5000}",
            initialDelayString = "${sanduo.command.scan-initial-delay-ms:10000}")
    public void scan() {
        try {
            commandService.timeoutScan();
        } catch (Exception e) {
            log.error("[Command] 超时扫描异常", e);
        }
    }
}
