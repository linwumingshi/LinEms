package com.energyx.ota.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * notify Feign 降级：通知服务不可用时记录日志，不阻断 OTA 主流程（告警是旁路能力）。
 */
@Slf4j
@Component
public class NotifyFeignClientFallbackFactory implements FallbackFactory<NotifyFeignClient> {

	@Override
	public NotifyFeignClient create(Throwable cause) {
		return body -> {
			log.warn("[OTA] 告警通知发送失败（notify 服务不可用或拒绝）: {}", cause == null ? "unknown" : cause.getMessage());
			return Result.fail(500, "告警通知不可用");
		};
	}

}
