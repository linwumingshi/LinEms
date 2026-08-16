package com.energyx.common.exception;

import com.energyx.common.model.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一转换为 {@link Result}，并透出 traceId 便于排障。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 处理业务异常 {@link BusinessException}，透传其业务码与提示。 */
	@ExceptionHandler(BusinessException.class)
	public Result<Void> handleBusiness(BusinessException e) {
		log.warn("business exception code={} msg={}", e.getCode(), e.getMessage());
		return Result.fail(e.getCode(), e.getMessage());
	}

	/**
	 * 业务校验失败（DSL 校验/乐观锁冲突/规则不存在等，IllegalArgumentException 语义=参数或业务校验错误）。 统一映射为
	 * {@link ErrorCode#BAD_REQUEST}。
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("illegal argument: {}", e.getMessage());
		return Result.fail(ErrorCode.BAD_REQUEST, e.getMessage());
	}

	/** 处理 @RequestBody 参数校验失败（JSR-303），返回首个字段错误，映射为 {@link ErrorCode#PARAM_INVALID}。 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
		FieldError fieldError = e.getBindingResult().getFieldError();
		String msg = fieldError == null ? ErrorCode.PARAM_INVALID.getMessage()
				: fieldError.getField() + " " + fieldError.getDefaultMessage();
		return Result.fail(ErrorCode.PARAM_INVALID, msg);
	}

	/** 处理表单/URL 参数绑定校验失败，返回首个字段错误，映射为 {@link ErrorCode#PARAM_INVALID}。 */
	@ExceptionHandler(BindException.class)
	public Result<Void> handleBind(BindException e) {
		FieldError fieldError = e.getBindingResult().getFieldError();
		String msg = fieldError == null ? ErrorCode.PARAM_INVALID.getMessage()
				: fieldError.getField() + " " + fieldError.getDefaultMessage();
		return Result.fail(ErrorCode.PARAM_INVALID, msg);
	}

	/** 处理方法级约束校验（@Validated 注解参数）失败，映射为 {@link ErrorCode#PARAM_INVALID}。 */
	@ExceptionHandler(ConstraintViolationException.class)
	public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
		return Result.fail(ErrorCode.PARAM_INVALID, e.getMessage());
	}

	/** 处理请求体反序列化失败（JSON 格式错误等），映射为 {@link ErrorCode#BAD_REQUEST}。 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
		return Result.fail(ErrorCode.BAD_REQUEST, "请求体格式错误");
	}

	/** 处理 HTTP 方法不支持，映射为 {@link ErrorCode#METHOD_NOT_ALLOWED}。 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		return Result.fail(ErrorCode.METHOD_NOT_ALLOWED);
	}

	/** 处理资源路径未找到（404），映射为 {@link ErrorCode#NOT_FOUND}。 */
	@ExceptionHandler(NoResourceFoundException.class)
	public Result<Void> handleNoResource(NoResourceFoundException e) {
		return Result.fail(ErrorCode.NOT_FOUND);
	}

	/** 兜底处理所有未被上述分支捕获的异常，映射为 {@link ErrorCode#SYSTEM_ERROR} 并记录错误日志。 */
	@ExceptionHandler(Throwable.class)
	public Result<Void> handleThrowable(Throwable e) {
		log.error("unhandled exception", e);
		return Result.fail(ErrorCode.SYSTEM_ERROR);
	}

}
