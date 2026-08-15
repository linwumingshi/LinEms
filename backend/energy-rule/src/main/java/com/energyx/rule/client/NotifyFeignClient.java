package com.energyx.rule.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * energy-notify 消息通知发送 Feign 客户端（Nacos 服务名 energy-notify 解析）。
 *
 * <p>
 * body 结构见 {@code NotifySendRequest}：configCode + templateCode + content + title +
 * context （context 为占位符上下文）。业务失败返回 HTTP 200 + code!=0 由上层 {@code NotifyAction} 处理。
 * </p>
 */
@FeignClient(name = "energy-notify", path = "/api/notify", fallbackFactory = NotifyFeignClientFallbackFactory.class)
public interface NotifyFeignClient {

	@PostMapping("/send")
	Result<Map<String, Object>> send(@RequestBody Map<String, Object> body);

}
