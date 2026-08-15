package com.energyx.access.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 产品服务 Feign 客户端（替代跨 schema 直查 es_product 物模型）。
 *
 * <p>
 * 接入链路仅需当前生效物模型的 schema_json 与版本号；未发布/禁用时 product 服务返回 code 非 0 的
 * Result，调用方视为无物模型不阻塞标准化链路。
 * </p>
 */
@FeignClient(name = "energy-product", path = "/api/product", fallbackFactory = ProductFeignClientFallbackFactory.class)
public interface ProductFeignClient {

	/** 按 productKey 查当前生效物模型；未发布/不存在返回 Result.fail */
	@GetMapping("/thing-model/by-key")
	Result<ThingModelRow> getThingModelByKey(@RequestParam("productKey") String productKey);

}
