package com.energyx.mock.client;

import com.energyx.common.model.Result;
import com.energyx.mock.client.dto.DeviceBrief;
import com.energyx.mock.client.dto.DeviceCreateReq;
import com.energyx.mock.client.dto.CredentialView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 设备中心 Feign 客户端（模拟器自动建档：创建设备 + 取回明文密钥）。
 *
 * <p>
 * 仅暴露自动建档所需的最小接口；服务间调用不经网关，无 JWT，租户上下文由 {@code TenantFeignInterceptor} 透传。
 * </p>
 */
@FeignClient(name = "energy-device", path = "/device", fallbackFactory = DeviceFeignClientFallbackFactory.class)
public interface DeviceFeignClient {

	/** 创建设备（返回设备主键；状态=INACTIVE，需 regenerateSecret 激活为 OFFLINE 方可接入） */
	@PostMapping
	Result<Long> create(@RequestBody DeviceCreateReq req);

	/** 激活设备（状态机 INACTIVE→OFFLINE；部分产品可能已自动激活，调用幂等） */
	@PostMapping("/{deviceId}/activate")
	Result<Void> activate(@PathVariable("deviceId") Long deviceId);

	/** 重新生成密钥：返回明文 deviceSecret 并激活为 OFFLINE(2)，模拟器据此过 broker 鉴权 */
	@PostMapping("/{deviceId}/credential/regenerate")
	Result<CredentialView> regenerateSecret(@PathVariable("deviceId") Long deviceId);

	/** 按 productKey+deviceName 查询设备（upsert 查重用）；不存在时成功但 data=null */
	@GetMapping("/by-name")
	Result<DeviceBrief> byName(@RequestParam("productKey") String productKey,
			@RequestParam("deviceName") String deviceName);

}
