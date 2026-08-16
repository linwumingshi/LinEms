package com.energyx.system.controller;

import com.energyx.common.model.Result;
import com.energyx.system.dto.SysPermissionSaveReq;
import com.energyx.system.entity.SysPermission;
import com.energyx.system.service.SysPermissionService;
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
 * 菜单资源管理接口。
 *
 * <p>
 * 权限标识见 V3 种子：system:perm:list / add / edit / remove。
 * </p>
 *
 * <ul>
 * <li>GET /system/perm/tree —— 查询全量菜单/按钮资源树</li>
 * <li>GET /system/perm/{permId} —— 查询单个菜单资源详情</li>
 * <li>POST /system/perm —— 新增菜单资源</li>
 * <li>PUT /system/perm/{permId} —— 更新菜单资源（含父节点移动）</li>
 * <li>DELETE /system/perm/{permId} —— 删除菜单资源并清理角色绑定</li>
 * <li>PUT /system/perm/{permId}/status —— 变更菜单资源正常/停用状态</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/system/perm")
public class SysPermissionController {

	private final SysPermissionService permissionService;

	public SysPermissionController(SysPermissionService permissionService) {
		this.permissionService = permissionService;
	}

	/**
	 * 查询全量菜单资源树（含目录、菜单与按钮节点），供菜单管理页渲染与角色权限分配勾选。
	 *
	 * <p>
	 * 内存组装树形结构：先按 sort 升序、permId 升序取全量记录，parentId 为 0 或父节点缺失的记录作为根节点， 其余挂到父节点 children 下。
	 * </p>
	 * @return {@link Result}<{@link List}<{@link SysPermission}>> 菜单资源根节点列表，子节点通过
	 * children 递归承载
	 */
	@GetMapping("/tree")
	@PreAuthorize("@ss.hasPermi('system:perm:list')")
	public Result<List<SysPermission>> tree() {
		return Result.ok(permissionService.tree());
	}

	/**
	 * 查询单个菜单资源详情（不含 children）。
	 * @param permId 菜单资源 ID（来源：路径变量）
	 * @return {@link Result}<{@link SysPermission}> 菜单资源信息；不存在时数据负载为 {@code null}
	 */
	@GetMapping("/{permId}")
	@PreAuthorize("@ss.hasPermi('system:perm:list')")
	public Result<SysPermission> detail(@PathVariable Long permId) {
		return Result.ok(permissionService.getById(permId));
	}

	/**
	 * 新增菜单资源（目录/菜单/按钮/数据权限节点）。
	 *
	 * <p>
	 * 校验父节点存在（parentId 为空或 0 时挂为顶级）、permCode 非空时全局唯一、permType 取值合法后落库； sort 默认 0、visible
	 * 默认 0（显示）、status 默认
	 * {@link com.energyx.common.enums.PermissionStatus#NORMAL}。写入成功后刷新全部在线会话权限，使新菜单/按钮即时生效。
	 * </p>
	 * @param req 请求体，字段说明见 {@link SysPermissionSaveReq}
	 * @return {@link Result}<{@link Long}> 新建菜单资源的 permId
	 * @throws com.energyx.common.exception.BusinessException
	 * 父节点不存在（404）、权限标识重复（409）、菜单类型非法（400）
	 */
	@PostMapping
	@PreAuthorize("@ss.hasPermi('system:perm:add')")
	public Result<Long> create(@Valid @RequestBody SysPermissionSaveReq req) {
		return Result.ok(permissionService.createPermission(req));
	}

	/**
	 * 更新菜单资源，支持通过 parentId 移动节点位置。
	 *
	 * <p>
	 * 校验 permCode 唯一（排除自身）、permType 合法、新父节点存在；并沿新父节点祖先链上溯做成环检测，
	 * 拒绝把节点挂到自身或自身子树下。写入成功后刷新全部在线会话权限。
	 * </p>
	 * @param permId 菜单资源 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link SysPermissionSaveReq}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException
	 * 菜单或父节点不存在（404）、权限标识重复或父节点成环（409）、菜单类型非法（400）
	 */
	@PutMapping("/{permId}")
	@PreAuthorize("@ss.hasPermi('system:perm:edit')")
	public Result<Void> update(@PathVariable Long permId, @Valid @RequestBody SysPermissionSaveReq req) {
		permissionService.updatePermission(permId, req);
		return Result.ok();
	}

	/**
	 * 删除菜单资源，同时清理其与角色的绑定关系。
	 *
	 * <p>
	 * 存在子节点时拒绝删除，需自下而上先删子节点；删除成功后刷新全部在线会话权限。
	 * </p>
	 * @param permId 菜单资源 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 菜单不存在（404）、存在子菜单（409）
	 */
	@DeleteMapping("/{permId}")
	@PreAuthorize("@ss.hasPermi('system:perm:remove')")
	public Result<Void> delete(@PathVariable Long permId) {
		permissionService.deletePermission(permId);
		return Result.ok();
	}

	/**
	 * 变更菜单资源状态（停用后前端不再渲染该菜单/按钮），变更后刷新全部在线会话权限。
	 * @param permId 菜单资源 ID（来源：路径变量）
	 * @param status 目标状态码（来源：查询参数），取值 0 正常 / 1 停用，语义见
	 * {@link com.energyx.common.enums.PermissionStatus}
	 * @return {@link Result}<{@link Void}> 无数据负载
	 * @throws com.energyx.common.exception.BusinessException 菜单不存在（404）
	 */
	@PutMapping("/{permId}/status")
	@PreAuthorize("@ss.hasPermi('system:perm:edit')")
	public Result<Void> changeStatus(@PathVariable Long permId, @RequestParam Integer status) {
		permissionService.changeStatus(permId, status);
		return Result.ok();
	}

}
