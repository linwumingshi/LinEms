package com.energyx.ems.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import com.energyx.ems.client.CommandFeignClient;
import com.energyx.ems.client.CommandViewDto;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用 energy-command POST /api/command 建指令（复用 QoS1/ACK 链路）。底层走 Feign（Nacos 服务名解析，无硬编码
 * URL）。
 */
@Component
public class CommandClient {

	private final CommandFeignClient feignClient;

	public CommandClient(CommandFeignClient feignClient) {
		this.feignClient = feignClient;
	}

	/**
	 * 调 energy-command POST /api/command，返回 commandId。 业务失败（code != 0 / 缺 commandId）抛
	 * {@link BusinessException}，携带 command 服务返回的 message。
	 */
	public String dispatch(String productKey, String deviceName, String command, Map<String, Object> params,
			long createBy) {
		Map<String, Object> body = new HashMap<>();
		body.put("productKey", productKey);
		body.put("deviceName", deviceName);
		body.put("command", command);
		body.put("commandType", 2);
		body.put("createBy", createBy);
		if (params != null) {
			body.put("params", params);
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
