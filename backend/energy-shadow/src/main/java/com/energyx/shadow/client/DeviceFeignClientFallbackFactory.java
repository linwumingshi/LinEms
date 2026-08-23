package com.energyx.shadow.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * device 服务不可用降级（M2.2）：返回业务失败 Result（data=null），Shadow desired 校验按 「设备解析失败」跳过校验不阻塞写入（保障
 * Shadow 链路可用）。
 */
@Slf4j
@Component
public class DeviceFeignClientFallbackFactory implements FallbackFactory<DeviceFeignClient> {

	/**
	 * 创建降级客户端：device 服务不可用时返回恒失败实现，desired 校验跳过。
	 * @param cause 触发降级的原始异常
	 * @return 降级用 {@link DeviceFeignClient} 实现
	 */
	@Override
	public DeviceFeignClient create(Throwable cause) {
		log.warn("[Shadow] device 服务调用失败，desired 校验降级跳过: {}", cause == null ? "unknown" : cause.getMessage());
		return new DeviceFeignClient() {
			@Override
			public Result<DeviceInfo> byId(long deviceId) {
				return Result.fail(50300, "device 服务不可用");
			}
		};
	}

}
