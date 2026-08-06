package com.sanduo.energy.system.controller;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.system.dto.SysRoleQuery;
import com.sanduo.energy.system.dto.SysRoleSaveReq;
import com.sanduo.energy.system.entity.SysRole;
import com.sanduo.energy.system.service.SysRoleService;
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
 * <p>权限标识见 V3 种子：system:role:list / add / edit / remove / perm。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/role")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public Result<PageResult<SysRole>> page(SysRoleQuery query) {
        return Result.ok(roleService.pageQuery(query));
    }

    /** 全量角色（用户分配角色下拉）。 */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public Result<List<SysRole>> list() {
        return Result.ok(roleService.listAll());
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public Result<SysRole> detail(@PathVariable Long roleId) {
        return Result.ok(roleService.getById(roleId));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:role:add')")
    public Result<Long> create(@Valid @RequestBody SysRoleSaveReq req) {
        return Result.ok(roleService.createRole(req));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    public Result<Void> update(@PathVariable Long roleId, @Valid @RequestBody SysRoleSaveReq req) {
        roleService.updateRole(roleId, req);
        return Result.ok();
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("@ss.hasPermi('system:role:remove')")
    public Result<Void> delete(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
        return Result.ok();
    }

    @PutMapping("/{roleId}/status")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    public Result<Void> changeStatus(@PathVariable Long roleId, @RequestParam Integer status) {
        roleService.changeStatus(roleId, status);
        return Result.ok();
    }

    /** 已分配权限 ID（回显勾选）。 */
    @GetMapping("/{roleId}/perms")
    @PreAuthorize("@ss.hasPermi('system:role:perm')")
    public Result<List<Long>> permIds(@PathVariable Long roleId) {
        return Result.ok(roleService.permIds(roleId));
    }

    /** 分配权限（全量覆盖，在线会话即时生效）。 */
    @PutMapping("/{roleId}/perms")
    @PreAuthorize("@ss.hasPermi('system:role:perm')")
    public Result<Void> assignPerms(@PathVariable Long roleId, @RequestBody List<Long> permIds) {
        roleService.assignPerms(roleId, permIds);
        return Result.ok();
    }
}
