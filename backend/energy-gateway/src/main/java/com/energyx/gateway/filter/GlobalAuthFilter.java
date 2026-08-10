package com.energyx.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.security.JwtClaims;
import com.energyx.security.JwtConstants;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenException;
import com.energyx.security.JwtTokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关鉴权门卫（P0-1 落地版）：
 * <ul>
 * <li>白名单（登录/验证码/健康检查）直接放行；</li>
 * <li>其余请求必须携带 {@code Authorization: Bearer &lt;JWT&gt;}，用 jjwt 验签 + 校验过期/issuer；</li>
 * <li>校验通过后把身份写入 x-user-id / x-user-name / x-tenant-id / x-enterprise-id 头透传下游，
 * 业务服务据此识别当前用户，不信任客户端自报身份；</li>
 * <li>过期 → 40101；缺失/伪造 → 40100。</li>
 * </ul>
 */
@Slf4j
@Component
public class GlobalAuthFilter implements GlobalFilter, Ordered {

	private static final String[] WHITE_PREFIXES = { "/api/system/auth", "/api/system/captcha", "/actuator",
			// WebSocket 升级请求（/ws/** → energy-alarm）：浏览器原生 WS 无法携带
			// Authorization 头，token 经查询参数传递，由下游 alarm 侧 WsAuthInterceptor
			// 自行 JWT 验签（网关只放行升级请求，REST 路径仍全部强制验签）。
			"/ws" };

	private static final String BODY_EXPIRED = "{\"code\":40101,\"message\":\"登录已过期，请重新登录\",\"data\":null}";

	private static final String BODY_UNAUTHORIZED = "{\"code\":40100,\"message\":\"未认证或 Token 无效\",\"data\":null}";

	private final ObjectMapper objectMapper;

	private final JwtProperties jwtProperties;

	public GlobalAuthFilter(ObjectMapper objectMapper, JwtProperties jwtProperties) {
		this.objectMapper = objectMapper;
		this.jwtProperties = jwtProperties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getURI().getPath();

		// CORS 预检直接放行（实际 CORS 由 CorsWebFilter 处理）
		if ("OPTIONS".equalsIgnoreCase(request.getMethod().name()) || isWhitelisted(path)) {
			return chain.filter(exchange);
		}

		String token = extractToken(request.getHeaders().getFirst(JwtConstants.AUTH_HEADER));
		if (token == null) {
			return unauthorized(exchange, BODY_UNAUTHORIZED);
		}
		try {
			JwtClaims claims = JwtTokenUtil.parse(jwtProperties, token);
			ServerHttpRequest mutated = request.mutate()
				.header(JwtConstants.HEADER_USER_ID, String.valueOf(claims.userId()))
				.header(JwtConstants.HEADER_USER_NAME, claims.username() == null ? "" : claims.username())
				.header(JwtConstants.HEADER_TENANT_ID, String.valueOf(claims.tenantId()))
				.header(JwtConstants.HEADER_ENTERPRISE_ID,
						claims.enterpriseId() == null ? "" : String.valueOf(claims.enterpriseId()))
				.build();
			return chain.filter(exchange.mutate().request(mutated).build());
		}
		catch (JwtTokenException e) {
			if (e.getReason() == JwtTokenException.Reason.EXPIRED) {
				log.debug("[Gateway] Token 过期 path={}", path);
				return unauthorized(exchange, BODY_EXPIRED);
			}
			log.debug("[Gateway] Token 无效 path={} reason={}", path, e.getMessage());
			return unauthorized(exchange, BODY_UNAUTHORIZED);
		}
	}

	private String extractToken(String authHeader) {
		if (authHeader != null && authHeader.startsWith(JwtConstants.BEARER_PREFIX)
				&& authHeader.length() > JwtConstants.BEARER_PREFIX.length()) {
			return authHeader.substring(JwtConstants.BEARER_PREFIX.length()).trim();
		}
		return null;
	}

	private boolean isWhitelisted(String path) {
		for (String prefix : WHITE_PREFIXES) {
			if (path.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private Mono<Void> unauthorized(ServerWebExchange exchange, String body) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatus.UNAUTHORIZED);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Mono.just(buffer));
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10; // 在 TraceIdFilter 之后
	}

}
