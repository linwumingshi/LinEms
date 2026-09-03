package com.energyx.common.tenant;

import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 Feign 租户透传自动装配。
 *
 * <p>
 * {@code @ConditionalOnClass(RequestInterceptor.class)} 保证仅在引入 feign
 * 的业务服务中装配；网关（WebFlux）、 消息网关（Netty）等未引入 openfeign 的服务虽扫描 com.energyx 也不会加载本类，避免
 * ClassNotFound。
 * </p>
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class TenantFeignAutoConfiguration {

	/**
	 * 注册租户透传拦截器，对容器内所有 {@code @FeignClient} 生效。
	 * @return 租户透传拦截器实例
	 */
	@Bean
	public RequestInterceptor tenantContextFeignInterceptor() {
		return new TenantContextFeignInterceptor();
	}

}
