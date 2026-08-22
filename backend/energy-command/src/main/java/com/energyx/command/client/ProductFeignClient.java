package com.energyx.command.client;

import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModelRow;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 产品服务 Feign 客户端（M2.1：Command 下发物模型校验）。
 *
 * <p>
 * 指令域仅需当前生效物模型的 schema_json（解析出 services 做白名单与入参校验）；未发布/不存在时 product 服务返回业务失败
 * Result，调用方按「无物模型」跳过校验不阻塞下发。契约与 access 侧 ProductFeignClient 一致（GET
 * /api/product/thing-model/by-key）。
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
