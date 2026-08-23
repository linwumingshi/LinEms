package com.energyx.shadow;

import com.energyx.common.thingmodel.ThingModelFetcher;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.shadow.client.ProductFeignClient;
import com.energyx.shadow.config.ShadowProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * energy-shadow 启动入口。
 *
 * <p>
 * M2.2 引入 Device/Product Feign（desired 物模型校验）需 @EnableFeignClients 扫描注册； M2.4 装配
 * ThingModelFetcher/ThingModelResolver 供 desired 校验共享。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@EnableScheduling
@MapperScan("com.energyx.shadow.mapper")
@EnableConfigurationProperties(ShadowProperties.class)
@EnableFeignClients(basePackages = "com.energyx.shadow.client")
public class ShadowApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShadowApplication.class, args);
	}

	/** M2.4：物模型获取回调绑定本服务 ProductFeignClient（common 不依赖 Feign） */
	@Bean
	ThingModelFetcher thingModelFetcher(ProductFeignClient productFeignClient) {
		return productFeignClient::getThingModelByKey;
	}

	/** M2.4：物模型 Resolver（L1 缓存 + 解析），供 setDesired 校验使用 */
	@Bean
	ThingModelResolver thingModelResolver(ThingModelFetcher thingModelFetcher) {
		return new ThingModelResolver(thingModelFetcher);
	}

}
