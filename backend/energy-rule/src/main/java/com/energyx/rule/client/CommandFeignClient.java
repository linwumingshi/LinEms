package com.energyx.rule.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * energy-command 指令创建 Feign 客户端（按 Nacos 服务名 energy-command 解析，无硬编码 URL）。
 *
 * <p>
 * 业务失败返回 HTTP 200 + Result code!=0，由上层
 * {@link com.energyx.rule.engine.action.DeviceCommandAction} 转执行失败记录。与 energy-ems 的
 * CommandFeignClient 同模式（本地投影，不依赖 energy-command 模块）。
 * </p>
 */
@FeignClient(name = "energy-command", path = "/api/command")
public interface CommandFeignClient {

	@PostMapping
	Result<CommandViewDto> create(@RequestBody Map<String, Object> body);

}
