package com.energyx.system.security;

import com.energyx.common.redis.RedisUtils;
import com.energyx.security.JwtClaims;
import com.energyx.security.JwtConstants;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenException;
import com.energyx.security.JwtTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 会话令牌服务（B 方案，对齐若依 TokenService）。
 *
 * <p>JWT 仅作为签名会话标识（携带 sid 指向 Redis），完整身份与权限缓存在
 * {@code auth:login_token:{sid}}；登出删除 Redis 键即吊销会话，角色权限变更可实时刷新。</p>
 *
 * <p>注：会话全量扫描使用 Redis KEYS（对齐若依），在线会话量大时建议升级为 SCAN 游标
 * 分片遍历（见 Phase9 容量项）。</p>
 */
@Slf4j
@Component
public class TokenService {

    private final RedisUtils redisUtils;
    private final JwtProperties jwtProperties;

    public TokenService(RedisUtils redisUtils, JwtProperties jwtProperties) {
        this.redisUtils = redisUtils;
        this.jwtProperties = jwtProperties;
    }

    /** 生成会话并签发 JWT。 */
    public String createToken(LoginUser loginUser) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(sid);
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + jwtProperties.getExpireSeconds() * 1000L);
        redisUtils.setJson(key(sid), loginUser, Duration.ofSeconds(jwtProperties.getExpireSeconds()));

        JwtClaims claims = new JwtClaims(loginUser.getUserId(), loginUser.getUsername(),
                loginUser.getTenantId(), loginUser.getEnterpriseId(), loginUser.getRealName(), sid);
        return JwtTokenUtil.sign(jwtProperties, claims);
    }

    /** 从请求解析出会话用户；token 缺失/无效/会话已吊销返回 null。 */
    public LoginUser getLoginUser(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null) {
            return null;
        }
        String sid;
        try {
            sid = JwtTokenUtil.parse(jwtProperties, token).sessionId();
        } catch (JwtTokenException e) {
            log.debug("[Token] 解析失败 reason={}", e.getReason());
            return null;
        }
        if (sid == null) {
            return null;
        }
        return redisUtils.getJson(key(sid), LoginUser.class);
    }

    /** 会话滑动续期：剩余有效期 ≤20 分钟时刷新 Redis 过期时间（对齐若依 verifyToken）。 */
    public void verifyToken(LoginUser loginUser) {
        long remaining = loginUser.getExpireTime() - System.currentTimeMillis();
        if (remaining <= AuthConstants.MILLIS_MINUTE_TWENTY) {
            refreshToken(loginUser);
        }
    }

    /** 登出吊销：从请求解析 token 并删除 Redis 会话（幂等）。 */
    public void logout(HttpServletRequest request) {
        delLoginUser(resolveToken(request));
    }

    /** 删除会话（登出吊销）。token 无效时静默忽略。 */
    public void delLoginUser(String token) {
        if (token == null) {
            return;
        }
        String sid;
        try {
            sid = JwtTokenUtil.parse(jwtProperties, token).sessionId();
        } catch (JwtTokenException e) {
            log.debug("[Token] 登出时解析失败，忽略 reason={}", e.getReason());
            return;
        }
        if (sid != null && redisUtils.delete(key(sid))) {
            log.info("[Token] 会话已吊销 sid={}", sid);
        }
    }

    /**
     * 角色权限变更后刷新所有持有该角色的在线会话权限（对齐若依 refreshPermissionByRoleId）。
     *
     * @param roleCode 变更的角色编码
     * @param resolver 权限计算器（计算用户最新权限集合）
     */
    public void refreshPermissionByRoleCode(String roleCode, PermissionResolver resolver) {
        var keys = redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int refreshed = 0;
        for (String key : keys) {
            LoginUser loginUser = redisUtils.getJson(key, LoginUser.class);
            if (loginUser == null || loginUser.getRoleCodes() == null
                    || !loginUser.getRoleCodes().contains(roleCode)) {
                continue;
            }
            loginUser.setPermissions(resolver.resolvePermissions(loginUser.getUserId(), loginUser.getRoleCodes()));
            refreshToken(loginUser);
            refreshed++;
        }
        if (refreshed > 0) {
            log.info("[Token] 角色[{}]权限变更，已刷新 {} 个在线会话", roleCode, refreshed);
        }
    }

    /**
     * 用户角色变更后刷新该用户的全部在线会话（角色集合 + 权限集合整体重算）。
     *
     * @param userId   变更角色的用户 ID
     * @param resolver 身份装配器（重算角色与权限）
     */
    public void refreshUserSessions(Long userId, PermissionResolver resolver) {
        var keys = redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int refreshed = 0;
        for (String key : keys) {
            LoginUser loginUser = redisUtils.getJson(key, LoginUser.class);
            if (loginUser == null || !userId.equals(loginUser.getUserId())) {
                continue;
            }
            PermissionResolver.ResolvedAuth auth = resolver.resolveUser(userId);
            loginUser.setRoleCodes(auth.roleCodes());
            loginUser.setPermissions(auth.permissions());
            refreshToken(loginUser);
            refreshed++;
        }
        if (refreshed > 0) {
            log.info("[Token] 用户[{}]角色变更，已刷新 {} 个在线会话", userId, refreshed);
        }
    }

    /** 吊销某用户的全部在线会话（重置密码/禁用/删除后强制重新登录）。 */
    public void revokeUserSessions(Long userId) {
        var keys = redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int revoked = 0;
        for (String key : keys) {
            LoginUser loginUser = redisUtils.getJson(key, LoginUser.class);
            if (loginUser == null || !userId.equals(loginUser.getUserId())) {
                continue;
            }
            redisUtils.delete(key);
            revoked++;
        }
        if (revoked > 0) {
            log.info("[Token] 用户[{}]全部会话已吊销，共 {} 个", userId, revoked);
        }
    }

    /**
     * 权限资源变更后全量刷新在线会话权限（角色集合不变，仅重算权限标识）。
     * 用于菜单/按钮增删改或停用后立即生效。
     *
     * @param resolver 权限计算器
     */
    public void refreshAllSessions(PermissionResolver resolver) {
        var keys = redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int refreshed = 0;
        for (String key : keys) {
            LoginUser loginUser = redisUtils.getJson(key, LoginUser.class);
            if (loginUser == null || loginUser.getRoleCodes() == null) {
                continue;
            }
            loginUser.setPermissions(resolver.resolvePermissions(loginUser.getUserId(), loginUser.getRoleCodes()));
            refreshToken(loginUser);
            refreshed++;
        }
        if (refreshed > 0) {
            log.info("[Token] 权限资源变更，已刷新 {} 个在线会话", refreshed);
        }
    }

    private void refreshToken(LoginUser loginUser) {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + jwtProperties.getExpireSeconds() * 1000L);
        redisUtils.setJson(key(loginUser.getToken()), loginUser,
                Duration.ofSeconds(jwtProperties.getExpireSeconds()));
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(JwtConstants.AUTH_HEADER);
        if (header != null && header.startsWith(JwtConstants.BEARER_PREFIX)) {
            return header.substring(JwtConstants.BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private String key(String sid) {
        return AuthConstants.LOGIN_TOKEN_KEY + sid;
    }
}
