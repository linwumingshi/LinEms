package com.energyx.ota.client;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ota.client.dto.DeviceQuery;
import com.energyx.ota.client.dto.DeviceUpdateReq;
import com.energyx.ota.client.dto.DeviceView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 设备中心 Feign 客户端（任务设备快照解析 + 升级成功版本回写）。
 */
@FeignClient(name = "energy-device", path = "/device", fallbackFactory = DeviceFeignClientFallbackFactory.class)
public interface DeviceFeignClient {

	/** 分页查询设备（任务创建时按产品解析目标设备） */
	@GetMapping("/page")
	Result<PageResult<DeviceView>> page(@SpringQueryMap DeviceQuery query);

	/** 更新设备（升级成功后回写 firmwareVersion） */
	@PutMapping("/{deviceId}")
	Result<Void> update(@PathVariable("deviceId") Long deviceId, @RequestBody DeviceUpdateReq req);

}
