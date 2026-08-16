package com.energyx.ota.client;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ota.client.dto.DeviceQuery;
import com.energyx.ota.client.dto.DeviceUpdateReq;
import com.energyx.ota.client.dto.DeviceView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * device 服务不可用降级：设备解析返回空（任务创建时报错提示），版本回写记日志不阻塞主流程。
 */
@Slf4j
@Component
public class DeviceFeignClientFallbackFactory implements FallbackFactory<DeviceFeignClient> {

	@Override
	public DeviceFeignClient create(Throwable cause) {
		log.warn("[OTA] device 服务调用失败: {}", cause == null ? "unknown" : cause.getMessage());
		return new DeviceFeignClient() {
			@Override
			public Result<PageResult<DeviceView>> page(DeviceQuery query) {
				return Result.fail(50300, "device 服务不可用");
			}

			@Override
			public Result<Void> update(Long deviceId, DeviceUpdateReq req) {
				return Result.fail(50300, "device 服务不可用");
			}
		};
	}

}
