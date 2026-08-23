package com.energyx.shadow.client;

import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModelRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * product 服务不可用降级（M2.2）：返回业务失败 Result（data=null），Shadow desired 校验按「无物模型」 跳过不阻塞写入（不把
 * Feign 异常变成 desired 写入失败）。
 */
@Slf4j
@Component
public class ProductFeignClientFallbackFactory implements FallbackFactory<ProductFeignClient> {

	/**
	 * 创建降级客户端：product 服务不可用时返回恒失败实现，desired 校验跳过。
	 * @param cause 触发降级的原始异常
	 * @return 降级用 {@link ProductFeignClient} 实现
	 */
	@Override
	public ProductFeignClient create(Throwable cause) {
		log.warn("[Shadow] product 服务调用失败，desired 物模型校验降级跳过: {}", cause == null ? "unknown" : cause.getMessage());
		return new ProductFeignClient() {
			@Override
			public Result<ThingModelRow> getThingModelByKey(String productKey) {
				return Result.fail(50300, "product 服务不可用");
			}
		};
	}

}
