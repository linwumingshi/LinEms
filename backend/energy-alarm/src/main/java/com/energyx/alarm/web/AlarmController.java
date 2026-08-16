package com.energyx.alarm.web;

import com.energyx.alarm.model.AlarmRuleRow;
import com.energyx.alarm.service.AlarmService;
import com.energyx.alarm.web.dto.AlarmAckRequest;
import com.energyx.alarm.web.dto.AlarmRecordView;
import com.energyx.alarm.web.dto.AlarmRuleSaveReq;
import com.energyx.alarm.web.dto.SceneAlarmRequest;
import com.energyx.common.enums.AlarmLevel;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <li>GET /api/alarm/records — 告警记录分页查询（tenant/rule/device/level/status/时间区间）；</li>
 * <li>POST /api/alarm/ack/{alarmEventId} — 人工确认（触发中/已恢复可确认，幂等）；</li>
 * <li>GET /api/alarm/rules — 启用规则列表（前端告警配置页）；</li>
 * <li>POST /api/alarm/rule — 新增告警规则；</li>
 * <li>PUT /api/alarm/rule/{ruleId} — 修改告警规则（rule_code 不可改）；</li>
 * <li>DELETE /api/alarm/rule/{ruleId} — 删除告警规则（物理删除）；</li>
 * <li>POST /api/alarm/trigger — 场景联动触发告警（RuleEngine ALARM 动作入口）。</li>
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

	/**
	 * 分页查询告警记录。
	 *
	 * <p>
	 * 支持按租户、规则、设备、告警级别、状态及触发时间区间筛选；page 默认从 1 开始，size 上限 200。
	 * </p>
	 * @param tenantId 租户ID（来源：查询参数，可空；缺省查全部租户）
	 * @param ruleId 规则ID（来源：查询参数，可空）
	 * @param deviceId 设备ID（来源：查询参数，可空）
	 * @param level 告警级别，1提示/2一般/3严重/4危急（来源：查询参数，可空）
	 * @param status 告警记录状态，0触发中/1已恢复/2已确认（来源：查询参数，可空）
	 * @param startTime 触发起始时间（ISO-8601，来源：查询参数，可空）
	 * @param endTime 触发结束时间（ISO-8601，来源：查询参数，可空）
	 * @param page 页码，从 1 开始（来源：查询参数，缺省 1）
	 * @param size 每页条数（来源：查询参数，缺省 20，上限 200）
	 * @return {@link Result}<{@link PageResult}<{@link AlarmRecordView}>> 告警记录分页结果
	 */
	@GetMapping("/records")
	public Result<PageResult<AlarmRecordView>> records(@RequestParam(required = false) Long tenantId,
			@RequestParam(required = false) Long ruleId, @RequestParam(required = false) Long deviceId,
			@RequestParam(required = false) Integer level, @RequestParam(required = false) Integer status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
			@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
		return Result
			.ok(alarmService.queryRecords(tenantId, ruleId, deviceId, level, status, startTime, endTime, page, size));
	}

	/**
	 * 人工确认告警。
	 *
	 * <p>
	 * 对触发中或已恢复状态的告警记录执行确认，记录确认人（终态重复确认为空操作，幂等）。 找不到记录或记录已确认时返回 404。
	 * </p>
	 * @param alarmEventId 告警事件ID（来源：路径变量）
	 * @param request 确认请求体，字段说明见 {@link AlarmAckRequest}
	 * @return {@link Result}<{@link Void}> 成功，或 404 记录不存在/已确认
	 */
	@PostMapping("/ack/{alarmEventId}")
	public Result<Void> ack(@PathVariable String alarmEventId, @Valid @RequestBody AlarmAckRequest request) {
		boolean ok = alarmService.ackAlarm(alarmEventId, request.getAckedBy());
		return ok ? Result.ok() : Result.fail(404, "告警记录不存在或已确认: " + alarmEventId);
	}

	/**
	 * 查询启用中的告警规则列表。
	 *
	 * <p>
	 * 返回缓存中状态为启用的规则，供前端告警配置页使用；传入 tenantId 时按租户过滤。
	 * </p>
	 * @param tenantId 租户ID（来源：查询参数，可空；缺省返回全部启用的规则）
	 * @return {@link Result}<{@link List}<{@link AlarmRuleRow}>> 启用的告警规则列表
	 */
	@GetMapping("/rules")
	public Result<List<AlarmRuleRow>> rules(@RequestParam(required = false) Long tenantId) {
		return Result.ok(alarmService.listRules(tenantId));
	}

	/**
	 * 新增告警规则。
	 *
	 * <p>
	 * 校验 condition/recovery JSON 及按触发类型补必填项，写库后立即刷新规则缓存。
	 * </p>
	 * @param req 告警规则请求体，字段说明见 {@link AlarmRuleSaveReq}
	 * @return {@link Result}<{@link Long}> 新规则主键 ruleId
	 */
	@PostMapping("/rule")
	public Result<Long> createRule(@Valid @RequestBody AlarmRuleSaveReq req) {
		return Result.ok(alarmService.createRule(req));
	}

	/**
	 * 修改告警规则。
	 *
	 * <p>
	 * 按 ruleId 更新规则（rule_code 不可改）；找不到规则返回 404，写库后刷新规则缓存。
	 * </p>
	 * @param ruleId 规则ID（来源：路径变量）
	 * @param req 告警规则请求体，字段说明见 {@link AlarmRuleSaveReq}
	 * @return {@link Result}<{@link Void}> 成功，或 404 规则不存在
	 */
	@PutMapping("/rule/{ruleId}")
	public Result<Void> updateRule(@PathVariable Long ruleId, @Valid @RequestBody AlarmRuleSaveReq req) {
		alarmService.updateRule(ruleId, req);
		return Result.ok();
	}

	/**
	 * 删除告警规则。
	 *
	 * <p>
	 * 物理删除规则；已产生的告警记录不受影响。找不到规则返回 404。
	 * </p>
	 * @param ruleId 规则ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 成功，或 404 规则不存在
	 */
	@DeleteMapping("/rule/{ruleId}")
	public Result<Void> deleteRule(@PathVariable Long ruleId) {
		alarmService.deleteRule(ruleId);
		return Result.ok();
	}

	/**
	 * 场景联动触发告警（Phase 11：RuleEngine ALARM 动作入口）。
	 *
	 * <p>
	 * 以「场景联动」名义创建告警记录（type=3 策略）并走既有发布链路；静默窗口防刷屏。 tenantId 缺省 1（单租户环境，多租户接入 TenantContext
	 * 后替换）。
	 * </p>
	 * @param request 场景告警请求体，字段说明见 {@link SceneAlarmRequest}
	 * @return {@link Result}<{@link Void}> 成功，或 409 参数缺失/静默期内触发失败
	 */
	@PostMapping("/trigger")
	public Result<Void> trigger(@Valid @RequestBody SceneAlarmRequest request) {
		AlarmLevel level = AlarmLevel.of(request.getSeverity());
		com.energyx.common.message.AlarmMessage alarm = alarmService.createSceneAlarm(1L, request.getDeviceId(),
				request.getProductKey(), request.getRuleCode(), level, request.getMessage(), request.getExt());
		return alarm == null ? Result.fail(409, "场景告警触发失败（参数缺失或静默期内）") : Result.ok();
	}

}
