package com.energyx.system.security;

import com.energyx.system.mapper.SysPermissionMapper;
import com.energyx.system.mapper.SysRoleMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 用户身份装配：加载角色集合与权限标识集合并应用超级管理员通配（对齐若依 *:*:*）。
 *
 * <p>
 * 登录、用户角色变更、角色权限变更、权限资源变更四处共用同一套计算逻辑， 保证会话刷新与初次登录结果一致。
 * </p>
 */
@Component
public class PermissionResolver {

	/** 装配结果：角色编码集合 + 权限标识集合。 */
	public record ResolvedAuth(Set<String> roleCodes, Set<String> permissions) {
	}

	private final SysPermissionMapper permissionMapper;

	private final SysRoleMapper roleMapper;

	public PermissionResolver(SysPermissionMapper permissionMapper, SysRoleMapper roleMapper) {
		this.permissionMapper = permissionMapper;
		this.roleMapper = roleMapper;
	}

	/** 完整装配用户身份（登录与用户角色变更刷新共用）。 */
	public ResolvedAuth resolveUser(Long userId) {
		Set<String> roleCodes = new HashSet<>(roleMapper.selectRoleCodesByUserId(userId));
		Set<String> permissions = resolvePermissions(userId, roleCodes);
		return new ResolvedAuth(roleCodes, permissions);
	}

	/** 按给定角色集合计算用户最新权限；超级管理员（角色 SUPER_ADMIN 或 userId=1）持有全部权限。 */
	public Set<String> resolvePermissions(Long userId, Set<String> roleCodes) {
		Set<String> permissions = new HashSet<>(permissionMapper.selectPermCodesByUserId(userId));
		if (roleCodes != null && (roleCodes.contains(AuthConstants.SUPER_ADMIN) || Long.valueOf(1L).equals(userId))) {
			permissions.clear();
			permissions.add(AuthConstants.ALL_PERMISSION);
		}
		return permissions;
	}

}
