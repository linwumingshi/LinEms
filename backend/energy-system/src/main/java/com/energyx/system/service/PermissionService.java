package com.energyx.system.service;

import com.energyx.system.security.AuthConstants;
import com.energyx.system.security.LoginUser;
import com.energyx.system.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 自定义权限实现（Bean 名 "ss"），供 {@code @PreAuthorize("@ss.hasPermi('system:user:list')")} 调用。
 * 对齐若依 PermissionService：持有 {@link AuthConstants#ALL_PERMISSION} 即全部放行。
 */
@Service("ss")
public class PermissionService {

    /** 验证用户是否具备某权限。 */
    public boolean hasPermi(String permission) {
        if (!StringUtils.hasText(permission)) {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getPermissions() == null || loginUser.getPermissions().isEmpty()) {
            return false;
        }
        return hasPermissions(loginUser.getPermissions(), permission);
    }

    /** 验证用户是否具备逗号分隔权限中的任意一个。 */
    public boolean hasAnyPermi(String permissions) {
        if (!StringUtils.hasText(permissions)) {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getPermissions() == null) {
            return false;
        }
        for (String permission : permissions.split(",")) {
            if (hasPermissions(loginUser.getPermissions(), permission)) {
                return true;
            }
        }
        return false;
    }

    /** 验证用户是否具备某角色（超级管理员恒真）。 */
    public boolean hasRole(String role) {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getRoleCodes() == null) {
            return false;
        }
        return loginUser.getRoleCodes().contains(AuthConstants.SUPER_ADMIN)
                || loginUser.getRoleCodes().contains(role);
    }

    private boolean hasPermissions(Set<String> permissions, String permission) {
        return permissions.contains(AuthConstants.ALL_PERMISSION)
                || permissions.contains(permission.trim());
    }
}
