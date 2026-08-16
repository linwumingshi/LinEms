package com.energyx.common.web;

import com.energyx.common.model.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用存活探针：所有依赖 energy-common 的 Servlet 服务自动获得 /ping。 用于网关连通性与健康检查（Phase 3 起）。
 */
@RestController
@RequestMapping("/ping")
public class PingController {

	@Value("${spring.application.name:unknown}")
	private String appName;

	/**
	 * 存活探针：返回服务名、版本与当前时间，供网关/负载均衡做连通性与健康检查。
	 * @return 成功 {@link Result}，data 含 service / version / time 字段
	 */
	@GetMapping
	public Result<Map<String, Object>> ping() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("service", appName);
		data.put("version", "1.0.0-SNAPSHOT");
		data.put("time", LocalDateTime.now().toString());
		return Result.ok(data);
	}

}
