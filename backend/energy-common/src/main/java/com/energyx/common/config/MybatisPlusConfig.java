package com.energyx.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.energyx.common.tenant.ConditionalTenantLineHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：条件化租户拦截器 + 分页插件。
 *
 * <p>
 * 租户拦截器是「条件化」的：仅当请求线程存在 {@link TenantContext}（即经网关透传 {@code x-tenant-id} 的 HTTP 请求）时，才对带
 * tenant_id 列的表追加租户条件；Kafka 消费线程、
 *
 * @Scheduled 调度线程、Netty 设备认证线程等无请求上下文，全部忽略 —— 保证消费写入/设备认证 等内部链路不被租户条件破坏。
 * </p>
 *
 * <p>
 * 注意：租户拦截器必须放在分页插件之前，否则分页的 count 语句不会携带租户条件。
 * </p>
 */
@Configuration
public class MybatisPlusConfig {

	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
		// 条件化租户拦截器 + 分页插件（租户在前，保证分页 count 也带租户条件）
		interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new ConditionalTenantLineHandler()));
		interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
		return interceptor;
	}

}
