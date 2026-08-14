package com.energyx.rule.client;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用 energy-command POST /api/command 建指令（复用 QoS1/ACK 链路）。
 *
 * <p>
 * 底层走 Feign（Nacos 服务名解析，无硬编码 URL）；业务失败（code != 0 / 缺 commandId） 抛
 * {@link BusinessException}，由动作执行器捕获记录到执行日志。
 * </p>
 */
@Component
public class CommandClient {

	private final CommandFeignClient feignClient;

	public CommandClient(CommandFeignClient feignClient) {
		this.feignClient = feignClient;
	}

	/**
	 * 调 energy-command POST /api/command，返回 commandId。
	 * @param productKey 产品标识
	 * @param deviceName 设备名
	 * @param command 物模型服务标识（如 setPower）
	 * @param params 命令参数（可空）
	 * @param createBy 发起人（规则引擎自动触发传 0）
	 * @param timeoutMs 指令超时（可空，缺省命令中心默认）
	 * @param maxRetry 最大重试（可空，缺省命令中心默认）
	 */
	public String dispatch(String productKey, String deviceName, String command, Map<String, Object> params,
			Integer timeoutMs, Integer maxRetry, long createBy) {
		Map<String, Object> body = new HashMap<>();
		body.put("productKey", productKey);
		body.put("deviceName", deviceName);
		body.put("command", command);
		body.put("commandType", 2);
		body.put("createBy", createBy);
		if (params != null) {
			body.put("params", params);
		}
		if (timeoutMs != null) {
			body.put("timeoutMs", timeoutMs);
		}
		if (maxRetry != null) {
			body.put("maxRetry", maxRetry);
		}
		Result<CommandViewDto> result = feignClient.create(body);
		if (result == null || !result.isSuccess()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST,
					"command 服务拒绝指令: " + (result == null ? "空响应" : result.getMessage()));
		}
		if (result.getData() == null || result.getData().getCommandId() == null) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务响应缺少 commandId");
		}
		return result.getData().getCommandId();
	}

}
