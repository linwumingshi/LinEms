package com.energyx.alarm.client;

import com.energyx.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * energy-product Feign 降级工厂：product 服务不可用/超时/异常时返回 productId=null，
 * 告警引擎按产品匹配退化为不命中（本消息跳过产品级规则），不阻塞消费与全局规则匹配。
 */
@Slf4j
@Component
public class ProductFeignClientFallbackFactory implements FallbackFactory<ProductFeignClient> {

	@Override
	public ProductFeignClient create(Throwable cause) {
		log.warn("[Alarm] product 服务调用降级（product_key 映射失败）: {}", cause == null ? "unknown" : cause.getMessage());
		return new ProductFeignClient() {
			@Override
			public Result<Long> productIdByKey(String productKey) {
				return Result.ok(null);
			}
		};
	}

}
