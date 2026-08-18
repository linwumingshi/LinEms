package com.energyx.mock.config;

import com.energyx.common.constant.Constants;
import com.energyx.common.tenant.TenantContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 租户透传拦截器：将当前线程 {@link TenantContext} 中的租户/企业标识写入下游 Feign 请求头， 保证模拟器内部调用
 * energy-device（创建设备/取密钥）时下游能拿到租户上下文（device 服务 requireTenant 强制要求）。
 *
 * <p>
 * 模拟器 REST 入口由 energy-common 的 {@code TenantContextFilter} 从 X-Tenant-Id 注入上下文； 同线程内同步发起的
 * Feign 调用（自动建档流程）即可经本拦截器透传。
 * </p>
 */
@Configuration
public class TenantFeignInterceptor implements RequestInterceptor {

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
