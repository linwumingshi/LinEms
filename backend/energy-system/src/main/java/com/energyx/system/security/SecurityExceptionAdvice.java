package com.energyx.system.security;

import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 方法级鉴权拒绝兜底：@PreAuthorize 抛出的 AuthorizationDeniedException （继承自 AccessDeniedException）在
 * DispatcherServlet 层被捕获，统一返回 403。
 *
 * <p>
 * 作用范围为本服务全部 {@code @RestController}。已通过认证但缺少所需权限标识（如
 * {@code @ss.hasPermi('system:user:add')} 校验失败）时，本处理器把异常转换为统一响应体 {@link Result}，业务码取
 * {@link ErrorCode#FORBIDDEN}，并记录 WARN 日志。
 * </p>
 *
 * <p>
 * 与之互补的两类前置拦截不在此处理：未携带/无效 token 由
 * {@link com.energyx.system.security.handle.JsonAuthenticationEntryPoint} 返回 401；
 * 过滤器链层面的访问拒绝由 {@link com.energyx.system.security.handle.JsonAccessDeniedHandler} 返回 403。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class SecurityExceptionAdvice {

	/**
	 * 处理方法级鉴权拒绝，统一返回 403 业务码。
	 * @param e Spring Security 抛出的访问拒绝异常（含 @PreAuthorize 校验失败产生的
	 * AuthorizationDeniedException）
	 * @return {@link Result}<{@link Void}> 业务码为 {@link ErrorCode#FORBIDDEN} 的失败响应，无数据负载
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public Result<Void> handleAccessDenied(AccessDeniedException e) {
		log.warn("access denied: {}", e.getMessage());
		return Result.fail(ErrorCode.FORBIDDEN);
	}

}
