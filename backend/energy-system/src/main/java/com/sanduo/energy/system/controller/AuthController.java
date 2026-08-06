package com.sanduo.energy.system.controller;

import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.system.dto.LoginRequest;
import com.sanduo.energy.system.dto.LoginResponse;
import com.sanduo.energy.system.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>网关路由 /api/system/** 时 StripPrefix=1，因此下游映射为 /system/auth/...，
 * 网关白名单前缀保持 /api/system/auth（网关侧去 StripPrefix 前的原始路径）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：校验账号密码并签发 JWT */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    /** 登出：吊销 Redis 会话（幂等，无 token / token 已失效同样返回成功） */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return Result.ok();
    }
}
