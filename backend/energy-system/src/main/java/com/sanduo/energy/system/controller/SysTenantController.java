package com.sanduo.energy.system.controller;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.system.dto.SysTenantQuery;
import com.sanduo.energy.system.dto.SysTenantSaveReq;
import com.sanduo.energy.system.entity.SysTenant;
import com.sanduo.energy.system.service.SysTenantService;
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
 */
@Slf4j
@RestController
@RequestMapping("/tenant")
public class SysTenantController {

    private final SysTenantService tenantService;

    public SysTenantController(SysTenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/page")
    public Result<PageResult<SysTenant>> page(SysTenantQuery query) {
        return Result.ok(tenantService.pageQuery(query));
    }

    @GetMapping("/{tenantId}")
    public Result<SysTenant> detail(@PathVariable Long tenantId) {
        return Result.ok(tenantService.getById(tenantId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody SysTenantSaveReq req) {
        return Result.ok(tenantService.createTenant(req));
    }

    @PutMapping("/{tenantId}")
    public Result<Void> update(@PathVariable Long tenantId, @Valid @RequestBody SysTenantSaveReq req) {
        tenantService.updateTenant(tenantId, req);
        return Result.ok();
    }

    @DeleteMapping("/{tenantId}")
    public Result<Void> delete(@PathVariable Long tenantId) {
        tenantService.removeById(tenantId);
        return Result.ok();
    }

    @PutMapping("/{tenantId}/status")
    public Result<Void> changeStatus(@PathVariable Long tenantId, @RequestParam Integer status) {
        tenantService.changeStatus(tenantId, status);
        return Result.ok();
    }
}
