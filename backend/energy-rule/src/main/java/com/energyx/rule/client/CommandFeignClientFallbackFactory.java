package com.energyx.rule.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * energy-command Feign 降级工厂：命令中心不可用时返回业务失败 Result（code=50300）， 由 DeviceCommandAction
 * 转执行失败记录，不阻断规则引擎其他动作。
 */
@Slf4j
@Component
public class CommandFeignClientFallbackFactory implements FallbackFactory<CommandFeignClient> {

	@Override
	public CommandFeignClient create(Throwable cause) {
		log.warn("[Rule] 命令服务调用降级: {}", cause == null ? "unknown" : cause.getMessage());
		return new CommandFeignClient() {
			@Override
			public Result<CommandViewDto> create(Map<String, Object> body) {
				return Result.fail(50300, "命令服务不可用");
			}
		};
	}

}
