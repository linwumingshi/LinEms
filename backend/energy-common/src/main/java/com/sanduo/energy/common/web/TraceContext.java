package com.sanduo.energy.common.web;

import com.sanduo.energy.common.constant.Constants;

import java.util.UUID;

/**
 * 全链路 TraceId 上下文（ThreadLocal）。
 * 由 {@link TraceFilter} 写入，贯穿一次请求的日志与响应。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        String traceId = TRACE_ID.get();
        return traceId == null ? Constants.EMPTY_TRACE_ID : traceId;
    }

    /** 生成 traceId，或沿用上游传入的（MQTT 报文/网关透传） */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
