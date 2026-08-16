package com.energyx.command.web.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令视图（查询/创建返回）。
 */
@Data
public class CommandView {

	/** 指令 ID（幂等键/主键），由服务端生成或客户端显式传入 */
	private String commandId;

	/** 租户 ID */
	private Long tenantId;

	/** 设备 ID */
	private Long deviceId;

	/** 产品标识（ProductKey） */
	private String productKey;

	/** 物模型服务标识，如 setPower / startCharge */
	private String command;

	/** 指令类型：1读取 2控制 */
	private Integer commandType;

	/** 指令参数（透传设备），键为参数名、值为参数值 */
	private Map<String, Object> params = new LinkedHashMap<>();

	/** 指令状态编码，含义见 {@code stateName} */
	private Integer state;

	/** 状态名：CREATED/SENT/DEVICE_RECEIVED/EXECUTING/SUCCESS/FAILED/TIMEOUT */
	private String stateName;

	/** 已重试次数 */
	private Integer retryCount;

	/** 最大重试次数（缺省 3） */
	private Integer maxRetry;

	/** 指令超时（毫秒，缺省 15000） */
	private Integer timeoutMs;

	/** 下发时间 */
	private LocalDateTime sentTime;

	/** 设备接收时间 */
	private LocalDateTime receivedTime;

	/** 开始执行时间 */
	private LocalDateTime executingTime;

	/** 完成时间 */
	private LocalDateTime finishTime;

	/** 执行结果（设备返回），键为属性名、值为属性值；成功/失败时按业务填充 */
	private Map<String, Object> result = new LinkedHashMap<>();

	/** 失败错误码（成功时为 null） */
	private String errorCode;

	/** 失败错误信息（成功时为 null） */
	private String errorMsg;

	/** 发起人（用户 ID；0 表示策略/影子自动下发） */
	private Long createBy;

	/** 创建时间 */
	private LocalDateTime createTime;

}
