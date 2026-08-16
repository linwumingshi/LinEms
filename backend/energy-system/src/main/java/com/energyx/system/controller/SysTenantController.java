package com.energyx.system.controller;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.system.dto.SysTenantQuery;
import com.energyx.system.dto.SysTenantSaveReq;
import com.energyx.system.entity.SysTenant;
import com.energyx.system.service.SysTenantService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理（Phase 3 垂直切片，作为各服务 CRUD 模式模板）。
 *
 * <p>
 * 租户表为多租户根表，不受租户插件按当前租户过滤，故本组接口面向平台级运营维护。
 * </p>
 *
 * <ul>
 * <li>GET /tenant/page —— 分页查询租户（支持编码/名称关键字筛选）</li>
 * <li>GET /tenant/{tenantId} —— 查询租户详情</li>
 * <li>POST /tenant —— 新增租户</li>
 * <li>PUT /tenant/{tenantId} —— 更新租户</li>
 * <li>DELETE /tenant/{tenantId} —— 删除租户（逻辑删除）</li>
 * <li>PUT /tenant/{tenantId}/status —— 变更租户启用/禁用状态</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/tenant")
public class SysTenantController {

	private final SysTenantService tenantService;

	public SysTenantController(SysTenantService tenantService) {
		this.tenantService = tenantService;
	}

	/**
	 * 分页查询租户列表。
	 *
	 * <p>
	 * 按 keyword 对租户编码/名称做模糊匹配，结果按 tenantId 倒序；每页大小上限 100，超出按 100 截断。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link SysTenantQuery}
	 * @return {@link Result}<{@link PageResult}<{@link SysTenant}>> 租户分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<SysTenant>> page(SysTenantQuery query) {
		return Result.ok(tenantService.pageQuery(query));
	}

	/**
	 * 查询单个租户详情。
	 * @param tenantId 租户 ID（来源：路径变量）
	 * @return {@link Result}<{@link SysTenant}> 租户信息；不存在时数据负载为 {@code null}
	 */
	@GetMapping("/{tenantId}")
	public Result<SysTenant> detail(@PathVariable Long tenantId) {
		return Result.ok(tenantService.getById(tenantId));
	}

	/**
	 * 新增租户。
	 *
	 * <p>
	 * 校验租户编码全局唯一后落库；status 未传时默认
	 * {@link com.energyx.common.enums.TenantStatus#ENABLED}（启用）。
	 * </p>
	 * @param req 请求体，字段说明见 {@link SysTenantSaveReq}
	 * @return {@link Result}<{@link Long}> 新建租户的 tenantId
	 * @throws com.energyx.common.exception.BusinessException 租户编码重复（409）
	 */
	@PostMapping
	public Result<Long> create(@Valid @RequestBody SysTenantSaveReq req) {
		return Result.ok(tenantService.createTenant(req));
	}

	/**
	 * 更新租户信息（编码、名称、联系人、联系电话、资源配额、状态）。
	 *
	 * <p>
	 * 租户编码发生变更时重新校验全局唯一。
	 * </p>
	 * @param tenantId 租户 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysTenantSaveReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 租户不存在（404）、租户编码重复（409）
	 */
	@PutMapping("/{tenantId}")
	public Result<Void> update(@PathVariable Long tenantId, @Valid @RequestBody SysTenantSaveReq req) {
		tenantService.updateTenant(tenantId, req);
		return Result.ok();
	}

	/**
	 * 删除租户，走 {@code @TableLogic} 逻辑删除（置 deleted=1），不做物理删除。
	 * @param tenantId 租户 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 */
	@DeleteMapping("/{tenantId}")
	public Result<Void> delete(@PathVariable Long tenantId) {
		tenantService.removeById(tenantId);
		return Result.ok();
	}

	/**
	 * 变更租户启用/禁用状态。
	 * @param tenantId 租户 ID（来源：路径变量）
	 * @param status 目标状态码（来源：查询参数），取值 0 禁用 / 1 启用，语义见
	 * {@link com.energyx.common.enums.TenantStatus}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 租户不存在（404）
	 */
	@PutMapping("/{tenantId}/status")
	public Result<Void> changeStatus(@PathVariable Long tenantId, @RequestParam Integer status) {
		tenantService.changeStatus(tenantId, status);
		return Result.ok();
	}

}
