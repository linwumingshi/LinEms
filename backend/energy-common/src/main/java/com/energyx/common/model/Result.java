package com.energyx.common.model;

import com.energyx.common.exception.ErrorCode;
import com.energyx.common.web.TraceContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * 统一响应体。 所有 REST 接口返回 {@link Result}，业务异常由
 * {@link com.energyx.common.exception.GlobalExceptionHandler} 兜底。
 *
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}：跨服务 Feign 反序列化时，对端可能返回 额外字段（如
 * isSuccess() 序列化的 "success"），忽略未知字段避免 FAIL_ON_UNKNOWN_PROPERTIES 报错。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Result<T> implements Serializable {

	private static final long serialVersionUID = 1L;

	/** 业务码：0 表示成功，非 0 见 {@link ErrorCode} 约定 */
	private int code;

	/** 提示信息：成功时为成功描述，失败时承载业务错误详情 */
	private String message;

	/** 业务数据负载：成功时承载返回对象，失败时通常为 {@code null} */
	private T data;

	/** 全链路追踪 ID（由 TraceFilter 注入，用于跨服务排障） */
	private String traceId;

	/** 响应生成时间戳，单位毫秒 */
	private long timestamp;

	public Result() {
		this.timestamp = System.currentTimeMillis();
		this.traceId = TraceContext.getTraceId();
	}

	/**
	 * 构建成功响应（无数据负载）。
	 * @param <T> 数据类型
	 * @return 业务码为 {@link ErrorCode#SUCCESS} 的 {@link Result}
	 */
	public static <T> Result<T> ok() {
		return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
	}

	/**
	 * 构建成功响应并携带数据。
	 * @param <T> 数据类型
	 * @param data 业务数据负载
	 * @return 业务码为 {@link ErrorCode#SUCCESS} 的 {@link Result}
	 */
	public static <T> Result<T> ok(T data) {
		return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
	}

	/**
	 * 构建失败响应，使用 {@link ErrorCode} 的默认提示信息。
	 * @param <T> 数据类型
	 * @param errorCode 业务错误码
	 * @return 失败 {@link Result}
	 */
	public static <T> Result<T> fail(ErrorCode errorCode) {
		return build(errorCode.getCode(), errorCode.getMessage(), null);
	}

	/**
	 * 构建失败响应，以自定义信息覆盖 {@link ErrorCode} 的默认提示。
	 * @param <T> 数据类型
	 * @param errorCode 业务错误码（取其业务码）
	 * @param message 自定义错误提示
	 * @return 失败 {@link Result}
	 */
	public static <T> Result<T> fail(ErrorCode errorCode, String message) {
		return build(errorCode.getCode(), message, null);
	}

	/**
	 * 构建失败响应，使用自定义业务码与提示信息。
	 * @param <T> 数据类型
	 * @param code 业务码
	 * @param message 错误提示
	 * @return 失败 {@link Result}
	 */
	public static <T> Result<T> fail(int code, String message) {
		return build(code, message, null);
	}

	private static <T> Result<T> build(int code, String message, T data) {
		Result<T> result = new Result<>();
		result.setCode(code);
		result.setMessage(message);
		result.setData(data);
		return result;
	}

	public boolean isSuccess() {
		return this.code == ErrorCode.SUCCESS.getCode();
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

}
