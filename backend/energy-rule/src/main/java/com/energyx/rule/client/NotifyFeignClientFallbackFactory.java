package com.energyx.rule.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * energy-notify Feign 降级工厂：通知服务不可用时返回业务失败 Result（code=50300）， 由 NotifyAction
 * 转执行失败记录，不阻断规则引擎其他动作。
 */
@Slf4j
@Component
public class NotifyFeignClientFallbackFactory implements FallbackFactory<NotifyFeignClient> {

	@Override
	public NotifyFeignClient create(Throwable cause) {
		log.warn("[Rule] 通知服务调用降级: {}", cause == null ? "unknown" : cause.getMessage());
		return new NotifyFeignClient() {
			@Override
			public Result<Map<String, Object>> send(Map<String, Object> body) {
				return Result.fail(50300, "通知服务不可用");
			}
		};
	}

}
