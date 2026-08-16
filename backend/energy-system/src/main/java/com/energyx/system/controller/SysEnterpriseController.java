package com.energyx.system.controller;

import com.energyx.common.model.Result;
import com.energyx.system.dto.SysEnterpriseSaveReq;
import com.energyx.system.entity.SysEnterprise;
import com.energyx.system.service.SysEnterpriseService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 单位管理接口（组织树）。
 *
 * <p>
 * 权限标识见 V3 种子：system:enterprise:list / add / edit / remove。
 * </p>
 *
 * <ul>
 * <li>GET /system/enterprise/tree —— 查询单位组织树</li>
 * <li>GET /system/enterprise/list —— 查询单位扁平列表，供父级下拉选择</li>
 * <li>GET /system/enterprise/{enterpriseId} —— 查询单位详情</li>
 * <li>POST /system/enterprise —— 新增单位并回填物化路径</li>
 * <li>PUT /system/enterprise/{enterpriseId} —— 更新单位（含父级移动与子树路径级联修正）</li>
 * <li>DELETE /system/enterprise/{enterpriseId} —— 删除单位</li>
 * <li>PUT /system/enterprise/{enterpriseId}/status —— 变更单位启用/禁用状态</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/system/enterprise")
public class SysEnterpriseController {

	private final SysEnterpriseService enterpriseService;

	public SysEnterpriseController(SysEnterpriseService enterpriseService) {
		this.enterpriseService = enterpriseService;
	}

	/**
	 * 查询单位组织树，供单位管理页渲染。
	 *
	 * <p>
	 * 内存组装树形结构：按 sort 升序、enterpriseId 升序取全量记录，parentId 为 0 或父节点缺失的记录作为根节点， 其余挂到父节点
	 * children 下。
	 * </p>
	 * @return {@link Result}<{@link List}<{@link SysEnterprise}>> 单位根节点列表，子节点通过 children
	 * 递归承载
	 */
	@GetMapping("/tree")
	@PreAuthorize("@ss.hasPermi('system:enterprise:list')")
	public Result<List<SysEnterprise>> tree() {
		return Result.ok(enterpriseService.tree());
	}

	/**
	 * 查询单位扁平列表（不组装树），按 sort 升序、enterpriseId 升序返回，供父单位下拉选择使用。
	 * @return {@link Result}<{@link List}<{@link SysEnterprise}>> 单位列表，children 字段不填充
	 */
	@GetMapping("/list")
	@PreAuthorize("@ss.hasPermi('system:enterprise:list')")
	public Result<List<SysEnterprise>> list() {
		return Result.ok(enterpriseService.listAll());
	}

	/**
	 * 查询单个单位详情（不含 children）。
	 * @param enterpriseId 单位 ID（来源：路径变量）
	 * @return {@link Result}<{@link SysEnterprise}> 单位信息；不存在时数据负载为 {@code null}
	 */
	@GetMapping("/{enterpriseId}")
	@PreAuthorize("@ss.hasPermi('system:enterprise:list')")
	public Result<SysEnterprise> detail(@PathVariable Long enterpriseId) {
		return Result.ok(enterpriseService.getById(enterpriseId));
	}

	/**
	 * 新增单位。
	 *
	 * <p>
	 * 校验同租户下单位编码唯一、父单位存在；parentId 为空或 0 时挂为顶级并置层级
	 * {@link com.energyx.common.enums.EnterpriseLevel#GROUP}，否则层级取父级 +1（上限 2，即子企业）。
	 * status 默认 1（启用）、sort 默认 0。物化路径依赖自增主键，落库后回填为「父 path + 自身 ID + /」。
	 * </p>
	 * @param req 请求体，字段说明见 {@link SysEnterpriseSaveReq}
	 * @return {@link Result}<{@link Long}> 新建单位的 enterpriseId
	 * @throws com.energyx.common.exception.BusinessException 单位编码重复（409）、父单位不存在（404）
	 */
	@PostMapping
	@PreAuthorize("@ss.hasPermi('system:enterprise:add')")
	public Result<Long> create(@Valid @RequestBody SysEnterpriseSaveReq req) {
		return Result.ok(enterpriseService.createEnterprise(req));
	}

	/**
	 * 更新单位，支持通过 parentId 移动单位在组织树中的位置。
	 *
	 * <p>
	 * 单位编码变更时重新校验同租户唯一；拒绝将父单位设为自身，或设为落在自身子树内的单位（按 path 前缀判定，防成环）。 移动导致 path
	 * 变化时，按旧路径前缀级联修正整棵子树的 path 与 level。
	 * </p>
	 * @param enterpriseId 单位 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysEnterpriseSaveReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 单位或父单位不存在（404）、单位编码重复或父单位成环（409）
	 */
	@PutMapping("/{enterpriseId}")
	@PreAuthorize("@ss.hasPermi('system:enterprise:edit')")
	public Result<Void> update(@PathVariable Long enterpriseId, @Valid @RequestBody SysEnterpriseSaveReq req) {
		enterpriseService.updateEnterprise(enterpriseId, req);
		return Result.ok();
	}

	/**
	 * 删除单位（逻辑删除）。
	 *
	 * <p>
	 * 存在子单位或单位下仍有用户时拒绝删除，需先清理下级单位与人员归属。
	 * </p>
	 * @param enterpriseId 单位 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 单位不存在（404）、存在子单位或单位下存在用户（409）
	 */
	@DeleteMapping("/{enterpriseId}")
	@PreAuthorize("@ss.hasPermi('system:enterprise:remove')")
	public Result<Void> delete(@PathVariable Long enterpriseId) {
		enterpriseService.deleteEnterprise(enterpriseId);
		return Result.ok();
	}

	/**
	 * 变更单位启用/禁用状态（仅更新自身节点，不级联子单位）。
	 * @param enterpriseId 单位 ID（来源：路径变量）
	 * @param status 目标状态码（来源：查询参数），取值 0 禁用 / 1 启用
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 单位不存在（404）
	 */
	@PutMapping("/{enterpriseId}/status")
	@PreAuthorize("@ss.hasPermi('system:enterprise:edit')")
	public Result<Void> changeStatus(@PathVariable Long enterpriseId, @RequestParam Integer status) {
		enterpriseService.changeStatus(enterpriseId, status);
		return Result.ok();
	}

}
