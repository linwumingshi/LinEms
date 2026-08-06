package com.sanduo.energy.system.security;

/**
 * 认证 / 权限相关常量。
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    /** 超级权限标识：持有则所有 @ss.hasPermi 直接放行 */
    public static final String ALL_PERMISSION = "*:*:*";

    /** 超级管理员角色编码 */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** Redis 会话键前缀（B 方案：login_tokens 语义），完整键 = 前缀 + 会话 uuid */
    public static final String LOGIN_TOKEN_KEY = "auth:login_token:";

    /** 会话滑动续期阈值：剩余有效期 ≤20 分钟则自动续期 */
    public static final long MILLIS_MINUTE_TWENTY = 20 * 60 * 1000L;
}
