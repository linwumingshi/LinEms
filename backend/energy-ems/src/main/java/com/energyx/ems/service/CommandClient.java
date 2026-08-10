package com.energyx.ems.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/** 调用 energy-command POST /api/command 建指令（复用 QoS1/ACK 链路）。薄封装，可替换为 Feign。 */
@Component
public class CommandClient {

	private final RestTemplate rest = new RestTemplate();

	@Value("${energyx.ems.command-base-url:http://127.0.0.1:8114}")
	private String baseUrl;

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
		ResponseEntity<Map> resp = rest.postForEntity(baseUrl + "/api/command", body, Map.class);
		Map<String, Object> result = resp.getBody();
		if (result == null) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务返回空响应");
		}
		if (!(result.get("code") instanceof Number n) || n.intValue() != 0) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务拒绝指令: " + result.get("message"));
		}
		Map<String, Object> data = (Map<String, Object>) result.get("data");
		if (data == null || data.get("commandId") == null) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务响应缺少 commandId");
		}
		return (String) data.get("commandId");
	}

}
