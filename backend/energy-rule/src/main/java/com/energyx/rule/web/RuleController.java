package com.energyx.rule.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.rule.service.RuleLogService;
import com.energyx.rule.service.RuleService;
import com.energyx.rule.web.dto.RuleLogView;
import com.energyx.rule.web.dto.RuleView;
import com.energyx.rule.web.dto.SaveRuleRequest;
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

/**
 * 场景联动规则管理 API。
 *
 * <ul>
 * <li>POST /api/rule 创建规则（DSL 校验 + 防环）；</li>
 * <li>PUT /api/rule/{ruleId} 更新规则（乐观锁 version）；</li>
 * <li>DELETE /api/rule/{ruleId} 删除规则（先停用后删）；</li>
 * <li>GET /api/rule/{ruleId} 详情；GET /api/rule/page 分页；</li>
 * <li>POST /api/rule/{ruleId}/enable|disable 启停；</li>
 * <li>GET /api/rule/log/page 执行日志分页。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rule")
public class RuleController {

	private final RuleService ruleService;

	private final RuleLogService ruleLogService;

	public RuleController(RuleService ruleService, RuleLogService ruleLogService) {
		this.ruleService = ruleService;
		this.ruleLogService = ruleLogService;
	}

	@PostMapping
	public Result<RuleView> create(@Valid @RequestBody SaveRuleRequest request) {
		return Result.ok(ruleService.create(request));
	}

	@PutMapping("/{ruleId}")
	public Result<RuleView> update(@PathVariable Long ruleId, @Valid @RequestBody SaveRuleRequest request) {
		return Result.ok(ruleService.update(ruleId, request));
	}

	@DeleteMapping("/{ruleId}")
	public Result<Void> delete(@PathVariable Long ruleId) {
		ruleService.delete(ruleId);
		return Result.ok();
	}

	@GetMapping("/{ruleId}")
	public Result<RuleView> get(@PathVariable Long ruleId) {
		RuleView view = ruleService.get(ruleId);
		return view == null ? Result.fail(404, "规则不存在: " + ruleId) : Result.ok(view);
	}

	@GetMapping("/page")
	public Result<PageResult<RuleView>> page(@RequestParam(required = false) Long tenantId,
			@RequestParam(required = false) String ruleName, @RequestParam(required = false) Integer enabled,
			@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
		return Result.ok(ruleService.page(tenantId, ruleName, enabled, page, size));
	}

	@PostMapping("/{ruleId}/enable")
	public Result<Void> enable(@PathVariable Long ruleId) {
		ruleService.setEnabled(ruleId, true);
		return Result.ok();
	}

	@PostMapping("/{ruleId}/disable")
	public Result<Void> disable(@PathVariable Long ruleId) {
		ruleService.setEnabled(ruleId, false);
		return Result.ok();
	}

	@GetMapping("/log/page")
	public Result<PageResult<RuleLogView>> logPage(@RequestParam(required = false) Long tenantId,
			@RequestParam(required = false) Long ruleId, @RequestParam(required = false) String triggerType,
			@RequestParam(required = false) Long deviceId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
			@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
		return Result.ok(ruleLogService.page(tenantId, ruleId, triggerType, deviceId, startTime, endTime, page, size));
	}

}
