package com.sanduo.energy.system.controller;

import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.system.dto.SysPermissionSaveReq;
import com.sanduo.energy.system.entity.SysPermission;
import com.sanduo.energy.system.service.SysPermissionService;
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
 * <p>权限标识见 V3 种子：system:perm:list / add / edit / remove。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/perm")
public class SysPermissionController {

    private final SysPermissionService permissionService;

    public SysPermissionController(SysPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 全量菜单树（含按钮，供管理页渲染与角色分配勾选）。 */
    @GetMapping("/tree")
    @PreAuthorize("@ss.hasPermi('system:perm:list')")
    public Result<List<SysPermission>> tree() {
        return Result.ok(permissionService.tree());
    }

    @GetMapping("/{permId}")
    @PreAuthorize("@ss.hasPermi('system:perm:list')")
    public Result<SysPermission> detail(@PathVariable Long permId) {
        return Result.ok(permissionService.getById(permId));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:perm:add')")
    public Result<Long> create(@Valid @RequestBody SysPermissionSaveReq req) {
        return Result.ok(permissionService.createPermission(req));
    }

    @PutMapping("/{permId}")
    @PreAuthorize("@ss.hasPermi('system:perm:edit')")
    public Result<Void> update(@PathVariable Long permId, @Valid @RequestBody SysPermissionSaveReq req) {
        permissionService.updatePermission(permId, req);
        return Result.ok();
    }

    @DeleteMapping("/{permId}")
    @PreAuthorize("@ss.hasPermi('system:perm:remove')")
    public Result<Void> delete(@PathVariable Long permId) {
        permissionService.deletePermission(permId);
        return Result.ok();
    }

    @PutMapping("/{permId}/status")
    @PreAuthorize("@ss.hasPermi('system:perm:edit')")
    public Result<Void> changeStatus(@PathVariable Long permId, @RequestParam Integer status) {
        permissionService.changeStatus(permId, status);
        return Result.ok();
    }
}
