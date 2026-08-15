package com.energyx.alarm.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * energy-product 产品信息 Feign 客户端（Nacos 服务名 energy-product 解析）。
 *
 * <p>
 * 替代跨库查询 es_product.iot_product：告警规则按 product_id 限定作用产品，而上报消息仅携带 product_key，本接口做
 * product_key → product_id 映射。调用失败由 {@link ProductFeignClientFallbackFactory} 降级返回
 * null（不阻塞告警引擎，规则退化为产品级不命中）。
 * </p>
 */
@FeignClient(name = "energy-product", path = "/api/product", fallbackFactory = ProductFeignClientFallbackFactory.class)
public interface ProductFeignClient {

	/** 按 productKey 查产品ID；产品不存在返回 null */
	@GetMapping("/by-key")
	Result<Long> productIdByKey(@RequestParam("productKey") String productKey);

}
