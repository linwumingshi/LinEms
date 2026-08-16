package com.energyx.system.controller;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.system.dto.SysRoleQuery;
import com.energyx.system.dto.SysRoleSaveReq;
import com.energyx.system.entity.SysRole;
import com.energyx.system.service.SysRoleService;
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
 * 角色管理接口。
 *
 * <p>
 * 权限标识见 V3 种子：system:role:list / add / edit / remove / perm。
 * </p>
 *
 * <ul>
 * <li>GET /system/role/page —— 分页查询角色（支持关键字、状态筛选）</li>
 * <li>GET /system/role/list —— 查询全量角色，供用户分配角色下拉</li>
 * <li>GET /system/role/{roleId} —— 查询角色详情</li>
 * <li>POST /system/role —— 新增角色</li>
 * <li>PUT /system/role/{roleId} —— 更新角色</li>
 * <li>DELETE /system/role/{roleId} —— 删除角色并清理权限绑定</li>
 * <li>PUT /system/role/{roleId}/status —— 变更角色启用/禁用状态</li>
 * <li>GET /system/role/{roleId}/perms —— 查询角色已分配权限 ID 集合</li>
 * <li>PUT /system/role/{roleId}/perms —— 全量覆盖式分配角色权限</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/system/role")
public class SysRoleController {

	private final SysRoleService roleService;

	public SysRoleController(SysRoleService roleService) {
		this.roleService = roleService;
	}

	/**
	 * 分页查询角色列表。
	 *
	 * <p>
	 * 按 keyword 对角色编码/名称做模糊匹配，可叠加状态过滤，结果按 roleId 倒序；每页大小上限 100，超出按 100 截断。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link SysRoleQuery}
	 * @return {@link Result}<{@link PageResult}<{@link SysRole}>> 角色分页结果
	 */
	@GetMapping("/page")
	@PreAuthorize("@ss.hasPermi('system:role:list')")
	public Result<PageResult<SysRole>> page(SysRoleQuery query) {
		return Result.ok(roleService.pageQuery(query));
	}

	/**
	 * 查询当前租户下全量角色，按 roleId 升序返回，供用户分配角色下拉使用（不分页、不过滤状态）。
	 * @return {@link Result}<{@link List}<{@link SysRole}>> 全量角色列表
	 */
	@GetMapping("/list")
	@PreAuthorize("@ss.hasPermi('system:role:list')")
	public Result<List<SysRole>> list() {
		return Result.ok(roleService.listAll());
	}

	/**
	 * 查询单个角色详情。
	 * @param roleId 角色 ID（来源：路径变量）
	 * @return {@link Result}<{@link SysRole}> 角色信息；角色不存在时数据负载为 {@code null}
	 */
	@GetMapping("/{roleId}")
	@PreAuthorize("@ss.hasPermi('system:role:list')")
	public Result<SysRole> detail(@PathVariable Long roleId) {
		return Result.ok(roleService.getById(roleId));
	}

	/**
	 * 新增角色。
	 *
	 * <p>
	 * 校验同租户下角色编码唯一后落库；status 未传时默认启用，dataScope 未传时默认
	 * {@link com.energyx.common.enums.DataScope#TENANT}（本租户）。
	 * </p>
	 * @param req 请求体，字段说明见 {@link SysRoleSaveReq}
	 * @return {@link Result}<{@link Long}> 新建角色的角色 ID
	 * @throws com.energyx.common.exception.BusinessException 角色编码重复（409）
	 */
	@PostMapping
	@PreAuthorize("@ss.hasPermi('system:role:add')")
	public Result<Long> create(@Valid @RequestBody SysRoleSaveReq req) {
		return Result.ok(roleService.createRole(req));
	}

	/**
	 * 更新角色基本信息（编码、名称、数据范围、状态）。
	 *
	 * <p>
	 * 角色编码变更时重新校验同租户唯一。本接口不涉及权限绑定调整，权限分配请用 {@code PUT /system/role/{roleId}/perms}。
	 * </p>
	 * @param roleId 角色 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysRoleSaveReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 角色不存在（404）、角色编码重复（409）
	 */
	@PutMapping("/{roleId}")
	@PreAuthorize("@ss.hasPermi('system:role:edit')")
	public Result<Void> update(@PathVariable Long roleId, @Valid @RequestBody SysRoleSaveReq req) {
		roleService.updateRole(roleId, req);
		return Result.ok();
	}

	/**
	 * 删除角色（逻辑删除），同时清理该角色的权限绑定。
	 *
	 * <p>
	 * 约束：内置超级管理员角色（roleId=1）不可删除；角色仍被用户引用时需先取消分配。
	 * </p>
	 * @param roleId 角色 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 删除内置超管角色或角色已分配给用户（409）、角色不存在（404）
	 */
	@DeleteMapping("/{roleId}")
	@PreAuthorize("@ss.hasPermi('system:role:remove')")
	public Result<Void> delete(@PathVariable Long roleId) {
		roleService.deleteRole(roleId);
		return Result.ok();
	}

	/**
	 * 变更角色启用/禁用状态。
	 *
	 * <p>
	 * 约束：内置超级管理员角色（roleId=1）不可禁用。
	 * </p>
	 * @param roleId 角色 ID（来源：路径变量）
	 * @param status 目标状态码（来源：查询参数），取值 0 禁用 / 1 启用，语义见
	 * {@link com.energyx.common.enums.RoleStatus}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 禁用内置超管角色（409）、角色不存在（404）
	 */
	@PutMapping("/{roleId}/status")
	@PreAuthorize("@ss.hasPermi('system:role:edit')")
	public Result<Void> changeStatus(@PathVariable Long roleId, @RequestParam Integer status) {
		roleService.changeStatus(roleId, status);
		return Result.ok();
	}

	/**
	 * 查询角色已分配的权限 ID 集合（供权限分配树回显勾选）。
	 * @param roleId 角色 ID（来源：路径变量）
	 * @return {@link Result}<{@link List}<{@link Long}>> 已分配权限（菜单/按钮）ID 列表，未分配时为空列表
	 */
	@GetMapping("/{roleId}/perms")
	@PreAuthorize("@ss.hasPermi('system:role:perm')")
	public Result<List<Long>> permIds(@PathVariable Long roleId) {
		return Result.ok(roleService.permIds(roleId));
	}

	/**
	 * 为角色分配权限（全量覆盖）。
	 *
	 * <p>
	 * 请求体权限 ID 去重后先校验全部存在，再清空该角色原有绑定并重建；完成后按角色编码刷新持有该角色的在线会话权限， 使变更即时生效。传空数组表示回收该角色全部权限。
	 * </p>
	 * @param roleId 角色 ID（来源：路径变量）
	 * @param permIds 请求体，目标权限（菜单/按钮）ID 集合（全量覆盖，重复项自动去重）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 角色不存在或权限不存在（404）
	 */
	@PutMapping("/{roleId}/perms")
	@PreAuthorize("@ss.hasPermi('system:role:perm')")
	public Result<Void> assignPerms(@PathVariable Long roleId, @RequestBody List<Long> permIds) {
		roleService.assignPerms(roleId, permIds);
		return Result.ok();
	}

}
