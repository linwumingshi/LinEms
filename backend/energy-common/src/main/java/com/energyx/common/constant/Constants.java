package com.energyx.common.constant;

/**
 * 全局常量。
 */
public final class Constants {

	private Constants() {
	}

	/** 全链路追踪 ID 请求头/响应头 */
	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	/** 租户透传请求头（网关写入） */
	public static final String TENANT_HEADER = "X-Tenant-Id";

	/** 企业透传请求头（网关写入，可空） */
	public static final String ENTERPRISE_HEADER = "X-Enterprise-Id";

	/** 用户透传请求头（网关写入） */
	public static final String USER_HEADER = "X-User-Id";

	/** 无 traceId 时的占位 */
	public static final String EMPTY_TRACE_ID = "0";

	/** 令牌请求头 */
	public static final String AUTH_HEADER = "Authorization";

	public static final String BEARER_PREFIX = "Bearer ";

}
