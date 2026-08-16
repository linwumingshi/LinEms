package com.energyx.rule.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.engine.RuleEngine;
import com.energyx.rule.engine.action.ActionResult;
import com.energyx.rule.service.RuleLogService;
import com.energyx.rule.service.RuleService;
import com.energyx.rule.web.dto.ManualTriggerRequest;
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
import java.util.List;

/**
 * 场景联动规则管理 API。
 *
 * <ul>
 * <li>POST /api/rule 创建规则（DSL 校验 + 防环）；</li>
 * <li>PUT /api/rule/{ruleId} 更新规则（乐观锁 version）；</li>
 * <li>DELETE /api/rule/{ruleId} 删除规则（先停用后删）；</li>
 * <li>GET /api/rule/{ruleId} 详情；GET /api/rule/page 分页；</li>
 * <li>POST /api/rule/{ruleId}/enable|disable 启停；</li>
 * <li>POST /api/rule/{ruleId}/trigger 手动触发（MANUAL 触发器规则，body 载荷注入上下文）；</li>
 * <li>GET /api/rule/log/page 执行日志分页。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rule")
public class RuleController {

	private final RuleService ruleService;

	private final RuleLogService ruleLogService;

	private final RuleEngine ruleEngine;

	public RuleController(RuleService ruleService, RuleLogService ruleLogService, RuleEngine ruleEngine) {
		this.ruleService = ruleService;
		this.ruleLogService = ruleLogService;
		this.ruleEngine = ruleEngine;
	}

	/**
	 * 创建场景联动规则。
	 *
	 * <p>
	 * 对 DSL 做格式校验并防环（循环触发检测），校验通过后在租户内保存唯一规则编码。
	 * </p>
	 * @param request 创建规则请求体，字段说明见 {@link SaveRuleRequest}
	 * @return {@link Result}<{@link RuleView}> 创建后的规则视图
	 */
	@PostMapping
	public Result<RuleView> create(@Valid @RequestBody SaveRuleRequest request) {
		return Result.ok(ruleService.create(request));
	}

	/**
	 * 更新场景联动规则。
	 *
	 * <p>
	 * 使用 {@code version} 字段做乐观锁，并发更新冲突将返回失败；更新同样走 DSL 校验与防环。
	 * </p>
	 * @param ruleId 规则 ID（路径变量）
	 * @param request 更新规则请求体，字段说明见 {@link SaveRuleRequest}
	 * @return {@link Result}<{@link RuleView}> 更新后的规则视图
	 */
	@PutMapping("/{ruleId}")
	public Result<RuleView> update(@PathVariable Long ruleId, @Valid @RequestBody SaveRuleRequest request) {
		return Result.ok(ruleService.update(ruleId, request));
	}

	/**
	 * 删除场景联动规则。
	 *
	 * <p>
	 * 先停用规则再删除（先停用后删），避免删除进行中的规则造成执行态不一致。
	 * </p>
	 * @param ruleId 规则 ID（路径变量）
	 * @return {@link Result}<{@link Void}> 删除成功无附加数据
	 */
	@DeleteMapping("/{ruleId}")
	public Result<Void> delete(@PathVariable Long ruleId) {
		ruleService.delete(ruleId);
		return Result.ok();
	}

	/**
	 * 查询规则详情。
	 * @param ruleId 规则 ID（路径变量）
	 * @return {@link Result}<{@link RuleView}> 规则视图；规则不存在时返回 404 失败
	 */
	@GetMapping("/{ruleId}")
	public Result<RuleView> get(@PathVariable Long ruleId) {
		RuleView view = ruleService.get(ruleId);
		return view == null ? Result.fail(404, "规则不存在: " + ruleId) : Result.ok(view);
	}

	/**
	 * 分页查询场景联动规则。
	 *
	 * <p>
	 * 支持按租户、规则名称模糊、启用状态筛选；未传筛选条件时返回当前租户全部规则分页。
	 * </p>
	 * @param tenantId 租户 ID（来源：查询参数，可选）
	 * @param ruleName 规则名称模糊匹配（来源：查询参数，可选）
	 * @param enabled 启用状态筛选，0=停用 1=启用（来源：查询参数，可选）
	 * @param page 页码，从 1 开始，缺省 1（来源：查询参数）
	 * @param size 每页条数，缺省 20（来源：查询参数）
	 * @return {@link Result}<{@link PageResult}<{@link RuleView}>> 规则分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<RuleView>> page(@RequestParam(required = false) Long tenantId,
			@RequestParam(required = false) String ruleName, @RequestParam(required = false) Integer enabled,
			@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
		return Result.ok(ruleService.page(tenantId, ruleName, enabled, page, size));
	}

	/**
	 * 启用规则。
	 * @param ruleId 规则 ID（路径变量）
	 * @return {@link Result}<{@link Void}> 启用成功无附加数据
	 */
	@PostMapping("/{ruleId}/enable")
	public Result<Void> enable(@PathVariable Long ruleId) {
		ruleService.setEnabled(ruleId, true);
		return Result.ok();
	}

	/**
	 * 停用规则。
	 * @param ruleId 规则 ID（路径变量）
	 * @return {@link Result}<{@link Void}> 停用成功无附加数据
	 */
	@PostMapping("/{ruleId}/disable")
	public Result<Void> disable(@PathVariable Long ruleId) {
		ruleService.setEnabled(ruleId, false);
		return Result.ok();
	}

	/**
	 * 手动触发规则（Phase 11 设计 §8.2）。
	 *
	 * <p>
	 * 要求规则含 MANUAL 触发器且处于启用状态；请求体 payload 注入 {@code RuleContext.payload}， 供条件求值与模板渲染引用。
	 * </p>
	 * @param ruleId 规则 ID（路径变量）
	 * @param request 手动触发请求体，字段说明见 {@link ManualTriggerRequest}（可空）
	 * @return {@link Result}<{@link List}<{@link ActionResult}>> 手动触发结果封装（当前 data 为 null）
	 */
	@PostMapping("/{ruleId}/trigger")
	public Result<List<ActionResult>> trigger(@PathVariable Long ruleId,
			@RequestBody(required = false) ManualTriggerRequest request) {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("MANUAL");
		ctx.setTs(System.currentTimeMillis());
		if (request != null && request.getPayload() != null) {
			ctx.setPayload(request.getPayload());
		}
		ruleEngine.onManual(ruleId, ctx);
		return Result.ok();
	}

	/**
	 * 分页查询规则执行日志。
	 *
	 * <p>
	 * 支持按租户、规则、触发器类型、设备、时间范围筛选。
	 * </p>
	 * @param tenantId 租户 ID（来源：查询参数，可选）
	 * @param ruleId 规则 ID（来源：查询参数，可选）
	 * @param triggerType 触发器类型 PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL/RULE（来源：查询参数，可选）
	 * @param deviceId 设备 ID（来源：查询参数，可选）
	 * @param startTime 筛选起始时间（ISO 日期时间，来源：查询参数，可选）
	 * @param endTime 筛选结束时间（ISO 日期时间，来源：查询参数，可选）
	 * @param page 页码，从 1 开始，缺省 1（来源：查询参数）
	 * @param size 每页条数，缺省 20（来源：查询参数）
	 * @return {@link Result}<{@link PageResult}<{@link RuleLogView}>> 执行日志分页结果
	 */
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
