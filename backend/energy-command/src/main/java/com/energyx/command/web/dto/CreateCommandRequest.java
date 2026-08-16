package com.energyx.command.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 创建指令请求体。
 *
 * <p>
 * commandId 可选：缺省由服务端生成；显式传入则作为幂等键（同 commandId 重试返回既有指令）。
 * </p>
 */
@Data
public class CreateCommandRequest {

	/** 幂等键（可选），如客户端生成的 UUID；显式传入则作为幂等键，重复提交返回既有指令 */
	private String commandId;

	/**
	 * 产品标识（必填）
	 * @required
	 */
	@NotBlank(message = "productKey 不能为空")
	private String productKey;

	/**
	 * 设备名称（必填）
	 * @required
	 */
	@NotBlank(message = "deviceName 不能为空")
	private String deviceName;

	/**
	 * 物模型服务标识，如 setPower / startCharge（必填）
	 * @required
	 */
	@NotBlank(message = "command 不能为空")
	private String command;

	/** 指令参数（透传设备），键为参数名、值为参数值 */
	private Map<String, Object> params;

	/** 指令类型：1读取 2控制（缺省 2） */
	private Integer commandType;

	/** 指令超时（毫秒，缺省 15000） */
	private Integer timeoutMs;

	/** 最大重试次数（缺省 3） */
	private Integer maxRetry;

	/** 发起人（用户 ID；0 表示策略/影子自动下发，可空） */
	private Long createBy;

}
