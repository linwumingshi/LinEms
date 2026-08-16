package com.energyx.system.controller;

import com.energyx.common.model.Result;
import com.energyx.system.dto.LoginRequest;
import com.energyx.system.dto.LoginResponse;
import com.energyx.system.service.AuthService;
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
 * <p>
 * 网关路由 /api/system/** 时 StripPrefix=1，因此下游映射为 /system/auth/...， 网关白名单前缀保持
 * /api/system/auth（网关侧去 StripPrefix 前的原始路径）。
 * </p>
 *
 * <ul>
 * <li>POST /system/auth/login —— 账号密码登录，签发 JWT 并返回权限/角色标识</li>
 * <li>POST /system/auth/logout —— 登出，吊销当前 Redis 会话</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/system/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/**
	 * 登录：校验账号密码并签发 JWT。
	 *
	 * <p>
	 * 认证委托 Spring Security
	 * {@link org.springframework.security.authentication.AuthenticationManager}，以
	 * {@code tenantId:username} 作为复合登录标识（sys_user 唯一键为 tenant_id + username）； 认证通过后写入
	 * Redis 会话并签发 JWT，同时刷新 sys_user.last_login_time（失败不阻塞登录）。
	 * </p>
	 *
	 * <p>
	 * 失败语义：账号不存在与密码错误统一返回「用户名或密码错误」（防账号枚举）；账号被禁用、被锁定分别返回独立提示。
	 * </p>
	 * @param req 请求体，字段说明见 {@link LoginRequest}
	 * @return {@link Result}<{@link LoginResponse}> JWT、有效期、用户基本信息及权限/角色标识集合
	 * @throws com.energyx.common.exception.BusinessException
	 * 用户名或密码为空、凭据错误（401）、账号禁用或锁定（403）
	 */
	@PostMapping("/login")
	public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
		return Result.ok(authService.login(req));
	}

	/**
	 * 登出：解析请求头中的 JWT 并吊销对应的 Redis 会话。
	 *
	 * <p>
	 * 幂等操作——未携带 token、token 非法或会话已失效时同样返回成功。
	 * </p>
	 * @param request HTTP 请求（用于获取 Header/鉴权信息）
	 * @return {@link Result}<{@link Void}> 无数据负载，成功即表示会话已失效
	 */
	@PostMapping("/logout")
	public Result<Void> logout(HttpServletRequest request) {
		authService.logout(request);
		return Result.ok();
	}

}
