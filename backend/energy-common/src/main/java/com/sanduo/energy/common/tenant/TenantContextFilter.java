package com.sanduo.energy.common.tenant;

import com.sanduo.energy.common.constant.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户上下文过滤器：从网关透传头 {@code x-tenant-id} / {@code x-enterprise-id} 读取当前租户，
 * 注入 {@link TenantContext}，请求结束后必须 clear（防 ThreadLocal 泄漏到线程池复用线程）。
 *
 * <p>Order 在 {@link com.sanduo.energy.common.web.TraceFilter}（HIGHEST_PRECEDENCE）之后；
 * 无头（内部调用 / 网关白名单路径）不设置上下文，租户拦截器据此跳过全部表。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long tenantId = parseLongHeader(request.getHeader(Constants.TENANT_HEADER));
        Long enterpriseId = parseLongHeader(request.getHeader(Constants.ENTERPRISE_HEADER));
        if (tenantId != null || enterpriseId != null) {
            TenantContext.set(new TenantInfo(tenantId, enterpriseId));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Long parseLongHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
