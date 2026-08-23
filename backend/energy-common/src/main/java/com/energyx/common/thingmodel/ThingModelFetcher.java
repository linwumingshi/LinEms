package com.energyx.common.thingmodel;

import com.energyx.common.model.Result;

/**
 * 物模型远程获取函数式接口（M2.4：解除 energy-common 对 Feign 的依赖）。
 *
 * <p>
 * 业务服务（Command/Shadow 等）各自装配本接口，绑定到自身模块的
 * {@code ProductFeignClient#getThingModelByKey}；common 不引入 Spring Cloud/OpenFeign。
 * </p>
 */
@FunctionalInterface
public interface ThingModelFetcher {

	/**
	 * 按 productKey 获取当前生效物模型投影。
	 * @param productKey 产品标识
	 * @return 物模型投影（data 为 null 表示无物模型/服务降级）
	 */
	Result<ThingModelRow> fetch(String productKey);

}
