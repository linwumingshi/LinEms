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

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_UNREGISTERED = 0;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_INACTIVE = 1;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_OFFLINE = 2;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_ONLINE = 3;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_DISABLED = 4;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.DeviceStatus} 替代
	 */
	@Deprecated
	public static final int DEVICE_STATUS_BANNED = 5;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_CREATED = 0;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_SENT = 1;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_DEVICE_RECEIVED = 2;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_EXECUTING = 3;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_SUCCESS = 4;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_FAILED = 5;

	/**
	 * @deprecated 使用 {@link com.energyx.common.enums.CommandState} 替代
	 */
	@Deprecated
	public static final int CMD_STATE_TIMEOUT = 6;

}
