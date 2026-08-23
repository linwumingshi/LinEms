package com.energyx.tsdb.client;

import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModelRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * product 服务不可用降级（M3.1）：返回业务失败 Result（data=null），自动 ALTER 按「物模型缺失」 跳过（该 stable 保持最终失败/DLQ
 * 语义），不把 Feign 异常吞成成功。
 */
@Slf4j
@Component
public class ProductFeignClientFallbackFactory implements FallbackFactory<ProductFeignClient> {

	/**
	 * 创建降级客户端：product 服务不可用时返回恒失败实现，自动 ALTER 跳过。
	 * @param cause 触发降级的原始异常
	 * @return 降级用 {@link ProductFeignClient} 实现
	 */
	@Override
	public ProductFeignClient create(Throwable cause) {
		log.warn("[Tsdb] product 服务调用失败，物模型 ALTER 降级跳过: {}", cause == null ? "unknown" : cause.getMessage());
		return new ProductFeignClient() {
			@Override
			public Result<ThingModelRow> getThingModelByKey(String productKey) {
				return Result.fail(50300, "product 服务不可用");
			}
		};
	}

}
