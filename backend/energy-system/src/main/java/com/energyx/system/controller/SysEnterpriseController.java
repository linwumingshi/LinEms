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
 * <p>权限标识见 V3 种子：system:enterprise:list / add / edit / remove。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/enterprise")
public class SysEnterpriseController {

    private final SysEnterpriseService enterpriseService;

    public SysEnterpriseController(SysEnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /** 组织树（管理页渲染）。 */
    @GetMapping("/tree")
    @PreAuthorize("@ss.hasPermi('system:enterprise:list')")
    public Result<List<SysEnterprise>> tree() {
        return Result.ok(enterpriseService.tree());
    }

    /** 扁平列表（父级下拉选择）。 */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('system:enterprise:list')")
    public Result<List<SysEnterprise>> list() {
        return Result.ok(enterpriseService.listAll());
    }

    @GetMapping("/{enterpriseId}")
    @PreAuthorize("@ss.hasPermi('system:enterprise:list')")
    public Result<SysEnterprise> detail(@PathVariable Long enterpriseId) {
        return Result.ok(enterpriseService.getById(enterpriseId));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:enterprise:add')")
    public Result<Long> create(@Valid @RequestBody SysEnterpriseSaveReq req) {
        return Result.ok(enterpriseService.createEnterprise(req));
    }

    @PutMapping("/{enterpriseId}")
    @PreAuthorize("@ss.hasPermi('system:enterprise:edit')")
    public Result<Void> update(@PathVariable Long enterpriseId, @Valid @RequestBody SysEnterpriseSaveReq req) {
        enterpriseService.updateEnterprise(enterpriseId, req);
        return Result.ok();
    }

    @DeleteMapping("/{enterpriseId}")
    @PreAuthorize("@ss.hasPermi('system:enterprise:remove')")
    public Result<Void> delete(@PathVariable Long enterpriseId) {
        enterpriseService.deleteEnterprise(enterpriseId);
        return Result.ok();
    }

    @PutMapping("/{enterpriseId}/status")
    @PreAuthorize("@ss.hasPermi('system:enterprise:edit')")
    public Result<Void> changeStatus(@PathVariable Long enterpriseId, @RequestParam Integer status) {
        enterpriseService.changeStatus(enterpriseId, status);
        return Result.ok();
    }
}
