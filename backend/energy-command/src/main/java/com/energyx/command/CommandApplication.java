package com.energyx.command;

import com.energyx.command.client.ProductFeignClient;
import com.energyx.command.config.CommandProperties;
import com.energyx.common.thingmodel.ThingModelFetcher;
import com.energyx.common.thingmodel.ThingModelResolver;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 指令中心启动入口。
 *
 * <p>
 * 扫描 {@code com.energyx}（含 energy-common 的通用组件）， Mapper 限定 command 域；@EnableScheduling 驱动
 * ACK 超时扫描；@EnableFeignClients 注册设备身份解析 Feign client（跨服务替代跨库查询）。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.command.mapper")
@EnableConfigurationProperties(CommandProperties.class)
@EnableFeignClients(basePackages = "com.energyx.command.client")
@EnableScheduling
public class CommandApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommandApplication.class, args);
	}

	/** M2.4：物模型获取回调绑定本服务 ProductFeignClient（common 不依赖 Feign） */
	@Bean
	ThingModelFetcher thingModelFetcher(ProductFeignClient productFeignClient) {
		return productFeignClient::getThingModelByKey;
	}

	/** M2.4：物模型 Resolver（L1 缓存 + 解析），供 createCommand / materializeDelta 校验共享 */
	@Bean
	ThingModelResolver thingModelResolver(ThingModelFetcher thingModelFetcher) {
		return new ThingModelResolver(thingModelFetcher);
	}

}
