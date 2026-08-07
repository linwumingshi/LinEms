package com.energyx.system.service;

import com.energyx.system.dto.LoginRequest;
import com.energyx.system.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证服务：登录签发 JWT、登出吊销 Redis 会话。
 */
public interface AuthService {

    /**
     * 用户名 + 密码登录。
     *
     * @param req 登录请求
     * @return JWT、用户基本信息与权限/角色标识
     * @throws com.energyx.common.exception.BusinessException 用户名/密码错误、账号禁用/锁定、参数缺失
     */
    LoginResponse login(LoginRequest req);

    /**
     * 登出：吊销当前请求携带的 Redis 会话（幂等）。
     *
     * @param request 当前 HTTP 请求（Authorization 头携带 JWT）
     */
    void logout(HttpServletRequest request);
}
