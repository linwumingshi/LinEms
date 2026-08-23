package com.energyx.shadow.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 设备服务 Feign 客户端（M2.2：desired 写入校验经 deviceId 解析 productKey）。
 *
 * <p>
 * 契约与 energy-command 的 DeviceFeignClient 一致（GET /device/{deviceId}）；跨模块边界不允许复用 command
 * 客户端，shadow 建同契约最小客户端。
 * </p>
 */
@FeignClient(name = "energy-device", path = "/device", fallbackFactory = DeviceFeignClientFallbackFactory.class)
public interface DeviceFeignClient {

	/** 按设备 ID 查设备；不存在返回 data=null */
	@GetMapping("/{deviceId}")
	Result<DeviceInfo> byId(@PathVariable("deviceId") long deviceId);

}
