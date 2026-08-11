package com.energyx.ems.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * energy-shadow 影子查询 Feign 客户端（按 Nacos 服务名 energy-shadow 解析，无硬编码 URL）。 查询失败/服务不可达抛
 * FeignException，由上层 {@link com.energyx.ems.service.ShadowClient} 兜底回退。
 */
@FeignClient(name = "energy-shadow", path = "/api/shadow")
public interface ShadowFeignClient {

	@GetMapping("/{deviceId}")
	Result<ShadowViewDto> getShadow(@PathVariable("deviceId") long deviceId);

}
