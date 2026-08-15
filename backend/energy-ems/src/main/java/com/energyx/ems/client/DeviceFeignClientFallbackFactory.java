package com.energyx.ems.client;

import com.energyx.common.model.Result;
import com.energyx.ems.model.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 设备服务不可用降级：返回业务失败 Result（data=null），调用方按空列表处理 （计划下发目标为空则跳过该站），不阻断调度主流程。
 */
@Slf4j
@Component
public class DeviceFeignClientFallbackFactory implements FallbackFactory<DeviceFeignClient> {

	@Override
	public DeviceFeignClient create(Throwable cause) {
		log.warn("[EMS] device 服务调用失败，降级返回空列表: {}", cause == null ? "unknown" : cause.getMessage());
		return new DeviceFeignClient() {
			@Override
			public Result<List<DeviceInfo>> listByStation(Long tenantId, Long stationId, String productKey,
					String deviceType) {
				return Result.fail(50300, "device 服务不可用");
			}
		};
	}

}
