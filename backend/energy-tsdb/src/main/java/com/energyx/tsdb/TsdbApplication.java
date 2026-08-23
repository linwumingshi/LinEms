package com.energyx.tsdb;

import com.energyx.common.thingmodel.ThingModelFetcher;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.tsdb.client.ProductFeignClient;
import com.energyx.tsdb.config.TsdbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * energy-tsdb 启动入口。
 *
 * <p>
 * 时序写入服务只依赖 TDengine 与 Redis（幂等），无 MySQL DataSource； 但 energy-common 传递引入
 * mybatis-plus/jdbc 自动配置，需排除 DataSource/MyBatis 三类自动配置，否则启动时因缺少数据源而失败。 M3.1
 * 起 @EnableFeignClients 注册 product Feign（缺列自动 ALTER 获取物模型）。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx",
		excludeName = { "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
				"org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
				"com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration" })
@EnableConfigurationProperties(TsdbProperties.class)
@EnableScheduling
@EnableFeignClients(basePackages = "com.energyx.tsdb.client")
public class TsdbApplication {

	public static void main(String[] args) {
		SpringApplication.run(TsdbApplication.class, args);
	}

	/** M3.1：物模型获取回调绑定本服务 ProductFeignClient（common 不依赖 Feign） */
	@Bean
	ThingModelFetcher thingModelFetcher(ProductFeignClient productFeignClient) {
		return productFeignClient::getThingModelByKey;
	}

	/** M3.1：物模型 Resolver（L1 缓存 + 解析），供缺列自动 ALTER 使用 */
	@Bean
	ThingModelResolver thingModelResolver(ThingModelFetcher thingModelFetcher) {
		return new ThingModelResolver(thingModelFetcher);
	}

}
