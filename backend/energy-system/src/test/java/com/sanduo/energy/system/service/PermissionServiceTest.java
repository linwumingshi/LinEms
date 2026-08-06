package com.sanduo.energy.system.service;

import com.sanduo.energy.system.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @ss 权限服务单元测试（PermissionService，Bean 名 "ss"）：
 * 精确权限、*:*:* 通配、未登录拒绝、角色判断。
 */
class PermissionServiceTest {

    private final PermissionService permissionService = new PermissionService();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Set<String> permissions, Set<String> roleCodes) {
        LoginUser loginUser = new LoginUser(1L, 1L, 1L, "admin", "admin",
                permissions, roleCodes, null, 1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @Test
    void hasPermi_allowsExactMatch() {
        loginAs(Set.of("system:user:list"), Set.of("OPERATOR"));
        assertTrue(permissionService.hasPermi("system:user:list"));
    }

    @Test
    void hasPermi_allowsWildcardForSuperAdmin() {
        loginAs(Set.of("*:*:*"), Set.of("SUPER_ADMIN"));
        assertTrue(permissionService.hasPermi("system:user:add"));
        assertTrue(permissionService.hasPermi("any:thing:here"));
    }

    @Test
    void hasPermi_deniesWhenMissing() {
        loginAs(Set.of("system:role:list"), Set.of("OPERATOR"));
        assertFalse(permissionService.hasPermi("system:user:list"));
    }

    @Test
    void hasPermi_deniesWhenNotAuthenticated() {
        SecurityContextHolder.clearContext();
        assertFalse(permissionService.hasPermi("system:user:list"));
    }

    @Test
    void hasAnyPermi_matchesAnyOfCsv() {
        loginAs(Set.of("system:user:list"), Set.of("OPERATOR"));
        assertTrue(permissionService.hasAnyPermi("system:user:add,system:user:list"));
        assertFalse(permissionService.hasAnyPermi("system:user:add,system:user:edit"));
    }

    @Test
    void hasRole_superAdminAlwaysTrue() {
        loginAs(Set.of("system:user:list"), Set.of("SUPER_ADMIN"));
        assertTrue(permissionService.hasRole("ANY_ROLE"));
    }

    @Test
    void hasRole_matchesExactRole() {
        loginAs(Set.of(), Set.of("OPERATOR"));
        assertTrue(permissionService.hasRole("OPERATOR"));
        assertFalse(permissionService.hasRole("VIEWER"));
    }

    @Test
    void hasPermi_emptyPermissionDenied() {
        loginAs(Set.of("*:*:*"), Set.of("SUPER_ADMIN"));
        assertFalse(permissionService.hasPermi("  "));
    }
}
