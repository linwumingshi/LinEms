package com.energyx.rule.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * energy-alarm 场景告警触发 Feign 客户端（Nacos 服务名 energy-alarm 解析）。
 *
 * <p>
 * 业务失败返回 HTTP 200 + Result code!=0，由上层 {@link com.energyx.rule.engine.action.AlarmAction}
 * 转执行失败记录。
 * </p>
 */
@FeignClient(name = "energy-alarm", path = "/api/alarm", fallbackFactory = AlarmFeignClientFallbackFactory.class)
public interface AlarmFeignClient {

	@PostMapping("/trigger")
	Result<Void> trigger(@RequestBody Map<String, Object> body);

}
