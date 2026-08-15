package com.energyx.access.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * product 服务不可用降级：返回业务失败 Result（data=null），接入链路按「无物模型」处理 （跳过标准化不阻塞），物模型缓存回源失败留待下次 TTL
 * 过期重试。
 */
@Slf4j
@Component
public class ProductFeignClientFallbackFactory implements FallbackFactory<ProductFeignClient> {

	@Override
	public ProductFeignClient create(Throwable cause) {
		log.warn("[Access] product 服务调用失败，降级返回 null: {}", cause == null ? "unknown" : cause.getMessage());
		return new ProductFeignClient() {
			@Override
			public Result<ThingModelRow> getThingModelByKey(String productKey) {
				return Result.fail(50300, "product 服务不可用");
			}
		};
	}

}
