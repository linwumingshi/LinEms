package com.sanduo.energy.system.service.impl;

import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.system.dto.LoginRequest;
import com.sanduo.energy.system.dto.LoginResponse;
import com.sanduo.energy.system.entity.SysUser;
import com.sanduo.energy.system.mapper.SysUserMapper;
import com.sanduo.energy.system.security.LoginUser;
import com.sanduo.energy.system.security.TokenService;
import com.sanduo.energy.system.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 认证服务实现（B 方案，对齐若依）：
 * 认证委托给 Spring Security {@link AuthenticationManager}（DaoAuthenticationProvider +
 * UserDetailsServiceImpl + PasswordEncoder），成功后由 {@link TokenService} 落 Redis 会话并签发 JWT。
 *
 * <p>安全语义：账号不存在与密码错误统一 401 提示（Provider hideUserNotFoundExceptions=true，
 * 防账号枚举）；禁用/锁定独立提示；失败记 WARN 日志。</p>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /** 默认租户 ID（单租户演示） */
    private static final long DEFAULT_TENANT_ID = 1L;

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final SysUserMapper userMapper;

    public AuthServiceImpl(AuthenticationManager authenticationManager, TokenService tokenService,
                           SysUserMapper userMapper) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    @Override
    public LoginResponse login(LoginRequest req) {
        if (req == null || !StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名和密码不能为空");
        }
        long tenantId = req.getTenantId() == null || req.getTenantId() <= 0 ? DEFAULT_TENANT_ID : req.getTenantId();
        String username = req.getUsername().trim();

        // 复合主键 "tenantId:username"（sys_user 唯一键 (tenant_id, username)）
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(tenantId + ":" + username, req.getPassword()));
        } catch (DisabledException e) {
            log.warn("[Auth] 账号被禁用 tenantId={} username={}", tenantId, username);
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        } catch (LockedException e) {
            log.warn("[Auth] 账号被锁定 tenantId={} username={}", tenantId, username);
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被锁定");
        } catch (BadCredentialsException e) {
            log.warn("[Auth] 用户名或密码错误 tenantId={} username={}", tenantId, username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        } catch (AuthenticationException e) {
            log.warn("[Auth] 认证异常 tenantId={} username={} type={}", tenantId, username,
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        String token = tokenService.createToken(loginUser);

        // 更新最后登录时间（不阻塞主流程）
        try {
            SysUser update = new SysUser();
            update.setUserId(loginUser.getUserId());
            update.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(update);
        } catch (Exception e) {
            log.error("[Auth] 更新最后登录时间失败 userId={}", loginUser.getUserId(), e);
        }

        log.info("[Auth] 登录成功 userId={} username={} tenantId={}", loginUser.getUserId(), username, tenantId);
        return buildResponse(token, loginUser);
    }

    @Override
    public void logout(HttpServletRequest request) {
        tokenService.logout(request);
    }

    private LoginResponse buildResponse(String token, LoginUser loginUser) {
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setTokenType("Bearer");
        resp.setExpiresIn((int) Math.max(1, (loginUser.getExpireTime() - loginUser.getLoginTime()) / 1000));
        resp.setUserId(loginUser.getUserId());
        resp.setUsername(loginUser.getUsername());
        resp.setRealName(loginUser.getRealName());
        resp.setTenantId(loginUser.getTenantId());
        resp.setEnterpriseId(loginUser.getEnterpriseId());
        resp.setPermissions(sortedCopy(loginUser.getPermissions()));
        resp.setRoles(sortedCopy(loginUser.getRoleCodes()));
        return resp;
    }

    /** 权限/角色标识排序后输出（Set 无序，输出端转为有序 List 便于前端稳定渲染）。 */
    private List<String> sortedCopy(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(new TreeSet<>(source));
    }
}
