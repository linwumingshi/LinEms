package com.energyx.common.tenant;

import com.energyx.common.constant.Constants;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 全局 Feign 租户透传拦截器：将当前线程 {@link TenantContext} 中的租户/企业标识写入下游 Feign 请求头。
 *
 * <p>
 * 跨服务调用（如 device→product、ems→tsdb 等）时，下游服务的 {@link TenantContextFilter} 依赖
 * {@code X-Tenant-Id} / {@code X-Enterprise-Id} 头恢复租户上下文。若不透传，下游 {@link TenantContext}
 * 为空，{@link ConditionalTenantLineHandler#ignoreTable} 会对所有表返回 {@code true}，导致 SQL 不拼租户条件、
 * 全量查询（越权）。本拦截器补齐这一环，保证租户隔离跨服务闭环。
 * </p>
 *
 * <p>
 * 由 {@link TenantFeignAutoConfiguration} 注册为全局 {@link RequestInterceptor}，默认作用于容器内所有
 * {@code @FeignClient}（除非客户端显式指定了独立 configuration）。
 * </p>
 */
public class TenantContextFeignInterceptor implements RequestInterceptor {

	@Override
	public void apply(RequestTemplate template) {
		Long tenantId = TenantContext.getTenantId();
		if (tenantId != null) {
			template.header(Constants.TENANT_HEADER, String.valueOf(tenantId));
		}
		Long enterpriseId = TenantContext.getEnterpriseId();
		if (enterpriseId != null) {
			template.header(Constants.ENTERPRISE_HEADER, String.valueOf(enterpriseId));
		}
	}

}
