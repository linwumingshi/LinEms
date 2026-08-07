package com.energyx.system.security;

import com.energyx.common.redis.RedisUtils;
import com.energyx.security.JwtClaims;
import com.energyx.security.JwtConstants;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话令牌服务单元测试：按角色/用户/全量刷新、按用户吊销、登出解析。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String SECRET = "test-secret-key-0123456789abcdefghijklmnopqrstuv";

    @Mock
    private RedisUtils redisUtils;
    @Mock
    private PermissionResolver resolver;

    private JwtProperties jwtProperties;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtProperties.setExpireSeconds(7200);
        jwtProperties.setIssuer("energyx-ems");
        tokenService = new TokenService(redisUtils, jwtProperties);
    }

    private LoginUser session(String sid, Long userId, Set<String> roleCodes) {
        LoginUser loginUser = new LoginUser(userId, 1L, 1L, "admin", "u" + userId,
                Set.of("old:perm"), roleCodes, null, 1);
        loginUser.setToken(sid);
        loginUser.setLoginTime(1_000L);
        loginUser.setExpireTime(1_000L + 7_200_000L);
        return loginUser;
    }

    @Test
    void refreshPermissionByRoleCode_refreshesOnlyMatchingRole() {
        when(redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*"))
                .thenReturn(Set.of("auth:login_token:s1", "auth:login_token:s2"));
        when(redisUtils.getJson("auth:login_token:s1", LoginUser.class))
                .thenReturn(session("s1", 1L, Set.of("OPERATOR")));
        when(redisUtils.getJson("auth:login_token:s2", LoginUser.class))
                .thenReturn(session("s2", 2L, Set.of("VIEWER")));
        when(resolver.resolvePermissions(1L, Set.of("OPERATOR"))).thenReturn(Set.of("system:user:list"));

        tokenService.refreshPermissionByRoleCode("OPERATOR", resolver);

        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(redisUtils).setJson(eq("auth:login_token:s1"), captor.capture(), any());
        assertEquals(Set.of("system:user:list"), captor.getValue().getPermissions());
        // 非目标角色会话不刷新
        verify(redisUtils, never()).setJson(eq("auth:login_token:s2"), any(), any());
    }

    @Test
    void refreshUserSessions_recomputesRolesAndPermissions() {
        when(redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*")).thenReturn(Set.of("auth:login_token:s1"));
        when(redisUtils.getJson("auth:login_token:s1", LoginUser.class))
                .thenReturn(session("s1", 1L, Set.of("OLD_ROLE")));
        when(resolver.resolveUser(1L))
                .thenReturn(new PermissionResolver.ResolvedAuth(Set.of("OPERATOR"), Set.of("system:role:list")));

        tokenService.refreshUserSessions(1L, resolver);

        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(redisUtils).setJson(eq("auth:login_token:s1"), captor.capture(), any());
        assertEquals(Set.of("OPERATOR"), captor.getValue().getRoleCodes());
        assertEquals(Set.of("system:role:list"), captor.getValue().getPermissions());
    }

    @Test
    void revokeUserSessions_deletesOnlyTargetUserKeys() {
        when(redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*"))
                .thenReturn(Set.of("auth:login_token:s1", "auth:login_token:s2", "auth:login_token:s3"));
        when(redisUtils.getJson("auth:login_token:s1", LoginUser.class)).thenReturn(session("s1", 1L, Set.of()));
        when(redisUtils.getJson("auth:login_token:s2", LoginUser.class)).thenReturn(session("s2", 2L, Set.of()));
        when(redisUtils.getJson("auth:login_token:s3", LoginUser.class)).thenReturn(session("s3", 1L, Set.of()));

        tokenService.revokeUserSessions(1L);

        verify(redisUtils).delete("auth:login_token:s1");
        verify(redisUtils).delete("auth:login_token:s3");
        verify(redisUtils, never()).delete("auth:login_token:s2");
    }

    @Test
    void refreshAllSessions_refreshesEverySession() {
        when(redisUtils.keys(AuthConstants.LOGIN_TOKEN_KEY + "*"))
                .thenReturn(Set.of("auth:login_token:s1", "auth:login_token:s2"));
        when(redisUtils.getJson("auth:login_token:s1", LoginUser.class))
                .thenReturn(session("s1", 1L, Set.of("OPERATOR")));
        when(redisUtils.getJson("auth:login_token:s2", LoginUser.class))
                .thenReturn(session("s2", 2L, Set.of("VIEWER")));
        when(resolver.resolvePermissions(1L, Set.of("OPERATOR"))).thenReturn(Set.of("a:perm"));
        when(resolver.resolvePermissions(2L, Set.of("VIEWER"))).thenReturn(Set.of("b:perm"));

        tokenService.refreshAllSessions(resolver);

        verify(redisUtils).setJson(eq("auth:login_token:s1"), any(), any());
        verify(redisUtils).setJson(eq("auth:login_token:s2"), any(), any());
    }

    @Test
    void logout_resolvesHeaderAndDeletesSession() {
        String token = JwtTokenUtil.sign(jwtProperties,
                new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", "sess-abc"));
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getHeader(JwtConstants.AUTH_HEADER)).thenReturn("Bearer " + token);
        when(redisUtils.delete("auth:login_token:sess-abc")).thenReturn(true);

        tokenService.logout(request);

        verify(redisUtils).delete("auth:login_token:sess-abc");
    }

    @Test
    void logout_withoutHeader_isNoOp() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getHeader(JwtConstants.AUTH_HEADER)).thenReturn(null);

        tokenService.logout(request);

        verify(redisUtils, never()).delete(any());
    }
}
