package com.energyx.common.model;

import com.energyx.common.exception.ErrorCode;
import com.energyx.common.web.TraceContext;

import java.io.Serializable;

/**
 * 统一响应体。
 * 所有 REST 接口返回 {@link Result}，业务异常由 {@link com.energyx.common.exception.GlobalExceptionHandler} 兜底。
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务码：0 成功，非 0 见 {@link ErrorCode} */
    private int code;
    private String message;
    private T data;
    /** 全链路追踪 ID（由 TraceFilter 注入） */
    private String traceId;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
        this.traceId = TraceContext.getTraceId();
    }

    public static <T> Result<T> ok() {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return build(errorCode.getCode(), message, null);
    }

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

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
