package com.energyx.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.energyx.common.tenant.ConditionalTenantLineHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局插件（租户隔离 + 分页）。
 *
 * <p>
 * <b>租户插件</b>：所有 SQL（含 BaseMapper 方法与注解 SQL）按当前租户上下文自动拼接 {@code tenant_id = 当前租户}
 * 条件；INSERT 自动填充 tenant_id 列。租户值由
 * {@link com.energyx.common.tenant.ConditionalTenantLineHandler} 从
 * {@link com.energyx.common.tenant.TenantContext} 动态取值：HTTP 请求经网关透传头注入，无租户上下文的非 HTTP
 * 线程（Kafka 消费 / @Scheduled / Netty 认证等）跳过租户改写。无 tenant_id 列的表需加入
 * {@link com.energyx.common.tenant.ConditionalTenantLineHandler#NO_TENANT_COLUMN_TABLES}
 * 忽略清单，否则插件拼接 条件会报列不存在。
 * </p>
 *
 * <p>
 * <b>分页插件</b>：BaseMapper.selectPage / wrapper 分页能力（Page 对象）。
 * </p>
 *
 * <p>
 * {@code @ConditionalOnClass} 保证仅引入 mybatis-plus 的服务（业务模块）装配； 网关等 WebFlux 服务虽扫描
 * com.energyx 但无该依赖，不会加载。
 * </p>
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPlusConfig {

	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

		// ✅ 修复：使用条件化租户处理器，替代硬编码匿名类
		interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new ConditionalTenantLineHandler()));

		// 分页（BaseMapper.selectPage 支持）
		interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
		return interceptor;
	}

}
