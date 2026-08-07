package com.energyx.common.web;

import com.energyx.common.constant.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器：优先沿用上游透传（网关/MQTT 桥接），否则生成，写入 MDC 与响应头。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(Constants.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.generate();
        }
        TraceContext.setTraceId(traceId);
        MDC.put(Constants.TRACE_ID_HEADER, traceId);
        response.setHeader(Constants.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(Constants.TRACE_ID_HEADER);
            TraceContext.clear();
        }
    }
}
