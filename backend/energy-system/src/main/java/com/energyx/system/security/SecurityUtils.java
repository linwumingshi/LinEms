package com.energyx.system.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录上下文工具。
 */
public final class SecurityUtils {

	private SecurityUtils() {
	}

	/** 从 SecurityContext 取当前登录用户，未认证返回 null。 */
	public static LoginUser getLoginUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
			return loginUser;
		}
		return null;
	}

	public static Long getUserId() {
		LoginUser loginUser = getLoginUser();
		return loginUser == null ? null : loginUser.getUserId();
	}

	public static Long getTenantId() {
		LoginUser loginUser = getLoginUser();
		return loginUser == null ? null : loginUser.getTenantId();
	}

}
