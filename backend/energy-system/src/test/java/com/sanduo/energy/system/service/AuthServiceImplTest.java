package com.sanduo.energy.system.service;

import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.system.dto.LoginRequest;
import com.sanduo.energy.system.dto.LoginResponse;
import com.sanduo.energy.system.entity.SysUser;
import com.sanduo.energy.system.mapper.SysUserMapper;
import com.sanduo.energy.system.security.LoginUser;
import com.sanduo.energy.system.security.TokenService;
import com.sanduo.energy.system.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试（Mockito，无 Spring 上下文）。
 * 认证委托 AuthenticationManager，登出委托 TokenService；本类聚焦异常映射与响应组装。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final long LOGIN_TIME = 1_000L;
    private static final long EXPIRE_TIME = 7_200_000L + LOGIN_TIME;

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private TokenService tokenService;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private Authentication authentication;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(authenticationManager, tokenService, userMapper);
    }

    private LoginUser loginUser() {
        LoginUser user = new LoginUser(1L, 1L, 1L, "系统管理员", "admin",
                Set.of("system:user:list", "*:*:*"), Set.of("SUPER_ADMIN"), "{noop}admin123", 1);
        user.setLoginTime(LOGIN_TIME);
        user.setExpireTime(EXPIRE_TIME);
        return user;
    }

    private LoginRequest validRequest() {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("admin123");
        return req;
    }

    private void mockAuthenticateSuccess() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(loginUser());
    }

    @Test
    void loginSuccess_returnsTokenPermissionsAndUpdatesLastLogin() {
        mockAuthenticateSuccess();
        when(tokenService.createToken(any(LoginUser.class))).thenReturn("jwt-token");

        LoginResponse resp = authService.login(validRequest());

        assertEquals("jwt-token", resp.getToken());
        assertEquals("Bearer", resp.getTokenType());
        assertEquals(7200, resp.getExpiresIn());
        assertEquals(1L, resp.getUserId());
        assertEquals("admin", resp.getUsername());
        assertEquals("系统管理员", resp.getRealName());
        assertEquals(List.of("*:*:*", "system:user:list"), resp.getPermissions());
        assertEquals(List.of("SUPER_ADMIN"), resp.getRoles());

        // 更新最后登录时间
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertNotNull(captor.getValue().getLastLoginTime());
    }

    @Test
    void loginWrongPassword_throwsUnauthorized() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(validRequest()));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void loginUserNotExist_throwsUnauthorized() {
        // Provider hideUserNotFoundExceptions=true：不存在与密码错误统一 BadCredentialsException
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        LoginRequest req = validRequest();
        req.setUsername("ghost");
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void loginDisabledUser_throwsForbidden() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new DisabledException("User is disabled"));

        LoginRequest req = validRequest();
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void loginLockedUser_throwsForbidden() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new LockedException("User account is locked"));

        LoginRequest req = validRequest();
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void loginBlankUsername_throwsParamMissing() {
        LoginRequest req = validRequest();
        req.setUsername("  ");
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.PARAM_MISSING.getCode(), ex.getCode());
    }

    @Test
    void loginBlankPassword_throwsParamMissing() {
        LoginRequest req = validRequest();
        req.setPassword(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.PARAM_MISSING.getCode(), ex.getCode());
    }

    @Test
    void logout_delegatesToTokenService() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        authService.logout(request);
        verify(tokenService).logout(request);
    }

    @Test
    void loginUsesTenantCompositePrincipal() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(loginUser());
        when(tokenService.createToken(any(LoginUser.class))).thenReturn("jwt-token");

        LoginRequest req = validRequest();
        req.setTenantId(1L);
        authService.login(req);

        // 复合主键 "tenantId:username"
        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertEquals("1:admin", captor.getValue().getPrincipal());
        assertEquals("admin123", captor.getValue().getCredentials());
    }
}
