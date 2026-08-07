package com.energyx.alarm.web;

import com.energyx.alarm.model.AlarmRuleRow;
import com.energyx.alarm.service.AlarmService;
import com.energyx.alarm.web.dto.AlarmAckRequest;
import com.energyx.alarm.web.dto.AlarmRecordView;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警中心 API。
 *
 * <ul>
 *   <li>GET  /api/alarm/records                告警记录分页查询（tenant/rule/device/level/status/时间区间）；</li>
 *   <li>POST /api/alarm/ack/{alarmEventId}     人工确认（触发中/已恢复可确认，幂等）；</li>
 *   <li>GET  /api/alarm/rules                  启用规则列表（前端告警配置页）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/alarm")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping("/records")
    public Result<PageResult<AlarmRecordView>> records(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(alarmService.queryRecords(tenantId, ruleId, deviceId, level, status,
                startTime, endTime, page, size));
    }

    @PostMapping("/ack/{alarmEventId}")
    public Result<Void> ack(@PathVariable String alarmEventId, @Valid @RequestBody AlarmAckRequest request) {
        boolean ok = alarmService.ackAlarm(alarmEventId, request.getAckedBy());
        return ok ? Result.ok() : Result.fail(404, "告警记录不存在或已确认: " + alarmEventId);
    }

    @GetMapping("/rules")
    public Result<List<AlarmRuleRow>> rules(@RequestParam(required = false) Long tenantId) {
        return Result.ok(alarmService.listRules(tenantId));
    }
}
