package com.energyx.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全链路 traceId 注入：请求缺失则生成，向下游服务传递并回写响应头。
 * 与各服务的 TraceFilter（energy-common）保持一致：头名 x-trace-id。
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "x-trace-id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String existing = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (StringUtils.hasText(existing)) {
            exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, existing);
            return chain.filter(exchange);
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        ServerHttpRequest mutated = request.mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
