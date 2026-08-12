package com.energyx.system.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.energyx.common.enums.UserStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录用户主体（B 方案 Redis 会话，对齐若依 LoginUser）。
 *
 * <p>
 * 认证期由 {@link UserDetailsServiceImpl} 构造，同时序列化至 Redis {@code auth:login_token:{token}}，供
 * {@code JwtAuthenticationTokenFilter} 每个请求恢复 SecurityContext 并驱动
 * {@code @ss.hasPermi}。password 不落 Redis（@JsonIgnore）。
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
public class LoginUser implements UserDetails, Serializable {

	private static final long serialVersionUID = 1L;

	private Long userId;

	private Long tenantId;

	private Long enterpriseId;

	private String realName;

	private String username;

	/** 权限标识集合（含 {@link AuthConstants#ALL_PERMISSION} 时全放行） */
	private Set<String> permissions;

	/** 角色编码集合 */
	private Set<String> roleCodes;

	/** 密码（仅认证期使用，不序列化到 Redis） */
	@JsonIgnore
	private String password;

	/** 用户状态（DISABLED/ENABLED/LOCKED，对应 DB 0禁用 1启用 2锁定） */
	private UserStatus status;

	/** 会话 ID（Redis 键 {@code auth:login_token:{token}}） */
	private String token;

	private long loginTime;

	private long expireTime;

	public LoginUser(Long userId, Long tenantId, Long enterpriseId, String realName, String username,
			Set<String> permissions, Set<String> roleCodes, String password, UserStatus status) {
		this.userId = userId;
		this.tenantId = tenantId;
		this.enterpriseId = enterpriseId;
		this.realName = realName;
		this.username = username;
		this.permissions = permissions;
		this.roleCodes = roleCodes;
		this.password = password;
		this.status = status;
	}

	@Override
	@JsonIgnore
	public Collection<? extends GrantedAuthority> getAuthorities() {
		if (roleCodes == null) {
			return Set.of();
		}
		return roleCodes.stream().map(code -> new SimpleGrantedAuthority("ROLE_" + code)).collect(Collectors.toSet());
	}

	@Override
	@JsonIgnore
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	@JsonIgnore
	public boolean isAccountNonLocked() {
		return status == null || status != UserStatus.LOCKED;
	}

	@Override
	@JsonIgnore
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	@JsonIgnore
	public boolean isEnabled() {
		return status != null && status == UserStatus.ENABLED;
	}

}
