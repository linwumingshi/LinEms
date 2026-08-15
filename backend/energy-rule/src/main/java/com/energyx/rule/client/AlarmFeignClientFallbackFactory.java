package com.energyx.rule.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * energy-alarm Feign 降级工厂：告警服务不可用时返回业务失败 Result（code=50300）， 由 AlarmAction
 * 转执行失败记录，不阻断规则引擎其他动作。
 */
@Slf4j
@Component
public class AlarmFeignClientFallbackFactory implements FallbackFactory<AlarmFeignClient> {

	@Override
	public AlarmFeignClient create(Throwable cause) {
		log.warn("[Rule] 告警服务调用降级: {}", cause == null ? "unknown" : cause.getMessage());
		return new AlarmFeignClient() {
			@Override
			public Result<Void> trigger(Map<String, Object> body) {
				return Result.fail(50300, "告警服务不可用");
			}
		};
	}

}
