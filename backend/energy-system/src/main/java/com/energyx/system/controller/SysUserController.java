package com.energyx.system.controller;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.system.dto.SysPasswordReq;
import com.energyx.system.dto.SysUserQuery;
import com.energyx.system.dto.SysUserSaveReq;
import com.energyx.system.dto.SysUserVO;
import com.energyx.system.service.SysUserService;
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
 * 用户管理接口。
 *
 * <p>权限标识见 V3 种子：system:user:list / add / edit / remove / resetPwd / role。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/user")
public class SysUserController {

    private final SysUserService userService;

    public SysUserController(SysUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public Result<PageResult<SysUserVO>> page(SysUserQuery query) {
        return Result.ok(userService.pageQuery(query));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public Result<SysUserVO> detail(@PathVariable Long userId) {
        return Result.ok(userService.detailVO(userId));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    public Result<Long> create(@Valid @RequestBody SysUserSaveReq req) {
        return Result.ok(userService.createUser(req));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    public Result<Void> update(@PathVariable Long userId, @Valid @RequestBody SysUserSaveReq req) {
        userService.updateUser(userId, req);
        return Result.ok();
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@ss.hasPermi('system:user:remove')")
    public Result<Void> delete(@PathVariable Long userId) {
        userService.deleteUser(userId);
        return Result.ok();
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    public Result<Void> changeStatus(@PathVariable Long userId, @RequestParam Integer status) {
        userService.changeStatus(userId, status);
        return Result.ok();
    }

    /** 重置密码（新密码必填，重置后吊销该用户在线会话） */
    @PutMapping("/{userId}/password")
    @PreAuthorize("@ss.hasPermi('system:user:resetPwd')")
    public Result<Void> resetPassword(@PathVariable Long userId, @Valid @RequestBody SysPasswordReq req) {
        userService.resetPassword(userId, req.getPassword());
        return Result.ok();
    }

    @GetMapping("/{userId}/roles")
    @PreAuthorize("@ss.hasPermi('system:user:role')")
    public Result<List<Long>> roleIds(@PathVariable Long userId) {
        return Result.ok(userService.roleIds(userId));
    }

    /** 分配角色（全量覆盖） */
    @PutMapping("/{userId}/roles")
    @PreAuthorize("@ss.hasPermi('system:user:role')")
    public Result<Void> assignRoles(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        userService.assignRoles(userId, roleIds);
        return Result.ok();
    }
}
