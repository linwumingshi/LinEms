package com.energyx.system.security;

import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 方法级鉴权拒绝兜底：@PreAuthorize 抛出的 AuthorizationDeniedException
 * （继承自 AccessDeniedException）在 DispatcherServlet 层被捕获，统一返回 403。
 */
@Slf4j
@RestControllerAdvice
public class SecurityExceptionAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("access denied: {}", e.getMessage());
        return Result.fail(ErrorCode.FORBIDDEN);
    }
}
