package com.energyx.mock.client;

import com.energyx.common.model.Result;
import com.energyx.mock.client.dto.DeviceBrief;
import com.energyx.mock.client.dto.DeviceCreateReq;
import com.energyx.mock.client.dto.CredentialView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * device 服务不可用降级：创建设备/取密钥失败，返回错误便于前端提示，不阻塞主流程。
 */
@Slf4j
@Component
public class DeviceFeignClientFallbackFactory implements FallbackFactory<DeviceFeignClient> {

	@Override
	public DeviceFeignClient create(Throwable cause) {
		log.warn("[MOCK] device 服务调用失败: {}", cause == null ? "unknown" : cause.getMessage());
		return new DeviceFeignClient() {
			@Override
			public Result<Long> create(DeviceCreateReq req) {
				return Result.fail(50300, "device 服务不可用");
			}

			@Override
			public Result<Void> activate(Long deviceId) {
				return Result.fail(50300, "device 服务不可用");
			}

			@Override
			public Result<CredentialView> regenerateSecret(Long deviceId) {
				return Result.fail(50300, "device 服务不可用");
			}

			@Override
			public Result<DeviceBrief> byName(String productKey, String deviceName) {
				return Result.fail(50300, "device 服务不可用");
			}
		};
	}

}
