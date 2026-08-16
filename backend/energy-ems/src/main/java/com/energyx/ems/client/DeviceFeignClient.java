package com.energyx.ems.client;

import com.energyx.common.model.Result;
import com.energyx.ems.model.DeviceInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 设备服务 Feign 客户端（替代跨 schema 直查 es_device.iot_device）。
 *
 * <p>
 * 按电站解析进线电表（deviceType=METER）与 PCS 下发目标（deviceType=PCS）， 仅返回可下发状态（已激活/在线）设备。
 * </p>
 */
@FeignClient(name = "energy-device", path = "/device", fallbackFactory = DeviceFeignClientFallbackFactory.class)
public interface DeviceFeignClient {

	/** 按电站 + 类型查设备列表；productKey/deviceType 可空过滤 */
	@GetMapping("/list-by-station")
	Result<List<DeviceInfo>> listByStation(@RequestParam("tenantId") Long tenantId,
			@RequestParam("stationId") Long stationId,
			@RequestParam(value = "productKey", required = false) String productKey,
			@RequestParam(value = "deviceType", required = false) String deviceType);

}
