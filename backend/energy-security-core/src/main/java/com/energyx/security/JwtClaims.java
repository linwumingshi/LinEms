package com.energyx.security;

/**
 * JWT 负载中的用户身份声明（不可变）。
 *
 * @param userId 用户 ID
 * @param username 登录名
 * @param tenantId 租户 ID
 * @param enterpriseId 所属企业 ID（可空）
 * @param realName 姓名（可空）
 * @param sessionId 会话 ID（Redis login_tokens:{sid} 键，可空=非会话令牌）
 */
public record JwtClaims(Long userId, String username, Long tenantId, Long enterpriseId, String realName,
		String sessionId) {
}
