package com.energyx.command.model;

import com.energyx.common.enums.CommandState;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * iot_command 行投影（params/result 以 JSON 字符串承载，解析在 service 层）。
 */
@Data
public class CommandRow {

	private String commandId;

	private Long tenantId;

	private Long deviceId;

	private String productKey;

	private String commandName;

	private Integer commandType;

	private String params;

	/**
	 * 指令生命周期状态（CREATED/SENT/DEVICE_RECEIVED/EXECUTING/SUCCESS/FAILED/TIMEOUT，对应 DB 0已创建
	 * 1已发送 2设备已接收 3执行中 4成功 5失败 6超时）
	 */
	private CommandState state;

	private Integer retryCount;

	private Integer maxRetry;

	private Integer timeoutMs;

	private LocalDateTime sentTime;

	private LocalDateTime receivedTime;

	private LocalDateTime executingTime;

	private LocalDateTime finishTime;

	private String result;

	private String errorCode;

	private String errorMsg;

	private Long createBy;

	private LocalDateTime createTime;

}
