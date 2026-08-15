package com.energyx.command.client;

import com.energyx.command.model.DeviceInfo;
import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 设备服务 Feign 客户端（替代跨 schema 直查 es_device.iot_device）。
 *
 * <p>
 * 指令域仅需设备身份（deviceId/tenantId/productKey/deviceName/status），JSON 字段与 energy-device 的
 * Device 实体兼容，反序列化为本地投影 {@link DeviceInfo}。
 * </p>
 */
@FeignClient(name = "energy-device", path = "/api/device", fallbackFactory = DeviceFeignClientFallbackFactory.class)
public interface DeviceFeignClient {

	/** 按设备 ID 查设备；不存在返回 data=null */
	@GetMapping("/{deviceId}")
	Result<DeviceInfo> byId(@PathVariable("deviceId") long deviceId);

	/** 按 productKey + deviceName 查设备；不存在返回 data=null */
	@GetMapping("/by-name")
	Result<DeviceInfo> byName(@RequestParam("productKey") String productKey,
			@RequestParam("deviceName") String deviceName);

}
