package com.energyx.command.client;

import com.energyx.command.model.DeviceInfo;
import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 设备服务不可用降级：返回业务失败 Result（data=null），调用方视为设备不存在， 指令创建/补发直接失败重试，不吞异常。
 */
@Slf4j
@Component
public class DeviceFeignClientFallbackFactory implements FallbackFactory<DeviceFeignClient> {

	@Override
	public DeviceFeignClient create(Throwable cause) {
		log.warn("[Command] device 服务调用失败，降级返回 null: {}", cause.getMessage());
		return new DeviceFeignClient() {
			@Override
			public Result<DeviceInfo> byId(long deviceId) {
				return Result.fail(50300, "device 服务不可用");
			}

			@Override
			public Result<DeviceInfo> byName(String productKey, String deviceName) {
				return Result.fail(50300, "device 服务不可用");
			}
		};
	}

}
