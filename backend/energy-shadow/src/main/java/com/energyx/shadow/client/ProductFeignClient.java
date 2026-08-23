package com.energyx.shadow.client;

import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModelRow;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 产品服务 Feign 客户端（M2.2：desired 写入校验获取当前生效物模型）。
 *
 * <p>
 * 复用已有 API {@code GET /api/product/thing-model/by-key}（与 access/command 同契约）；返回
 * energy-common 的 {@link ThingModelRow}，未发布/不存在返回业务失败 Result，调用方按「无物模型」跳过校验。
 * </p>
 */
@FeignClient(name = "energy-product", path = "/api/product", fallbackFactory = ProductFeignClientFallbackFactory.class)
public interface ProductFeignClient {

	/**
	 * 按 productKey 查当前生效物模型；未发布/不存在返回 Result.fail。
	 * @param productKey 产品标识
	 * @return 当前生效物模型投影（data 为 null 表示无物模型）
	 */
	@GetMapping("/thing-model/by-key")
	Result<ThingModelRow> getThingModelByKey(@RequestParam("productKey") String productKey);

}
