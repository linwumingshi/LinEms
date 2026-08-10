package com.energyx.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.security.JwtClaims;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关鉴权过滤器测试。 注：Spring 6.1 已移除 MockServerWebExchange，这里用真实 MockServerHttpRequest/Response
 * + Mockito mock 的 ServerWebExchange 组装，验证放行/验签透传/401 语义。
 */
class GlobalAuthFilterTest {

	private static final String SECRET = "test-secret-key-0123456789abcdefghijklmnopqrstuv";

	private JwtProperties jwtProperties;

	private GlobalAuthFilter filter;

	@BeforeEach
	void setUp() {
		jwtProperties = new JwtProperties();
		jwtProperties.setSecret(SECRET);
		jwtProperties.setExpireSeconds(7200);
		jwtProperties.setIssuer("energyx-ems");
		filter = new GlobalAuthFilter(new ObjectMapper(), jwtProperties);
	}

	/** 记录转发到下游 exchange 的桩链 */
	private static final class CapturingChain implements GatewayFilterChain {

		private final List<ServerWebExchange> forwarded = new CopyOnWriteArrayList<>();

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			forwarded.add(exchange);
			return Mono.empty();
		}

	}

	private ServerWebExchange mockExchange(MockServerHttpRequest request, MockServerHttpResponse response) {
		ServerWebExchange exchange = mock(ServerWebExchange.class);
		when(exchange.getRequest()).thenReturn(request);
		when(exchange.getResponse()).thenReturn(response);
		return exchange;
	}

	/**
	 * 装配 exchange.mutate() 链路：mock builder，捕获传入的 mutated request， builder.build() 返回新的
	 * mock exchange（供 chain 记录）。
	 */
	private ServerWebExchange wireMutation(ServerWebExchange exchange,
			ArgumentCaptor<ServerHttpRequest> requestCaptor) {
		ServerWebExchange.Builder builder = mock(ServerWebExchange.Builder.class);
		when(exchange.mutate()).thenReturn(builder);
		when(builder.request(requestCaptor.capture())).thenReturn(builder);
		ServerWebExchange mutated = mock(ServerWebExchange.class);
		when(builder.build()).thenReturn(mutated);
		return mutated;
	}

	private String signToken(JwtProperties props, JwtClaims claims) {
		return JwtTokenUtil.sign(props, claims);
	}

	@Test
	void whitelistedLogin_forwardedWithoutToken() {
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(MockServerHttpRequest.post("/api/system/auth/login").build(), response);
		CapturingChain chain = new CapturingChain();
		filter.filter(ex, chain).block();
		assertEquals(1, chain.forwarded.size());
	}

	@Test
	void whitelistedActuator_forwardedWithoutToken() {
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(MockServerHttpRequest.get("/actuator/health").build(), response);
		CapturingChain chain = new CapturingChain();
		filter.filter(ex, chain).block();
		assertEquals(1, chain.forwarded.size());
	}

	@Test
	void validToken_forwardsIdentityHeaders() {
		String token = signToken(jwtProperties, new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", null));
		MockServerHttpRequest request = MockServerHttpRequest.get("/api/device/list")
			.header("Authorization", "Bearer " + token)
			.build();
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(request, response);
		ArgumentCaptor<ServerHttpRequest> requestCaptor = ArgumentCaptor.forClass(ServerHttpRequest.class);
		ServerWebExchange mutated = wireMutation(ex, requestCaptor);
		CapturingChain chain = new CapturingChain();

		filter.filter(ex, chain).block();

		ServerHttpRequest forwarded = requestCaptor.getValue();
		assertEquals("1", forwarded.getHeaders().getFirst("x-user-id"));
		assertEquals("admin", forwarded.getHeaders().getFirst("x-user-name"));
		assertEquals("1", forwarded.getHeaders().getFirst("x-tenant-id"));
		assertEquals("1", forwarded.getHeaders().getFirst("x-enterprise-id"));
		assertEquals(1, chain.forwarded.size());
		assertEquals(mutated, chain.forwarded.get(0));
	}

	@Test
	void validToken_withoutEnterpriseId_forwardsEmptyHeader() {
		String token = signToken(jwtProperties, new JwtClaims(2L, "ops", 1L, null, null, null));
		MockServerHttpRequest request = MockServerHttpRequest.get("/api/alarm/list")
			.header("Authorization", "Bearer " + token)
			.build();
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(request, response);
		ArgumentCaptor<ServerHttpRequest> requestCaptor = ArgumentCaptor.forClass(ServerHttpRequest.class);
		wireMutation(ex, requestCaptor);
		CapturingChain chain = new CapturingChain();

		filter.filter(ex, chain).block();

		assertEquals("", requestCaptor.getValue().getHeaders().getFirst("x-enterprise-id"));
	}

	@Test
	void missingToken_returns401() {
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(MockServerHttpRequest.get("/api/device/list").build(), response);
		CapturingChain chain = new CapturingChain();
		filter.filter(ex, chain).block();

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		assertEquals(0, chain.forwarded.size());
		assertTrue(response.getBodyAsString().block().contains("40100"));
	}

	@Test
	void malformedToken_returns401() {
		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(
				MockServerHttpRequest.get("/api/device/list").header("Authorization", "Bearer not.a.jwt").build(),
				response);
		CapturingChain chain = new CapturingChain();
		filter.filter(ex, chain).block();

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		assertEquals(0, chain.forwarded.size());
		assertTrue(response.getBodyAsString().block().contains("40100"));
	}

	@Test
	void expiredToken_returns401WithExpiredCode() {
		JwtProperties expired = new JwtProperties();
		expired.setSecret(SECRET);
		expired.setIssuer("energyx-ems");
		expired.setExpireSeconds(-1); // 立即过期
		String token = signToken(expired, new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", null));

		MockServerHttpResponse response = new MockServerHttpResponse();
		ServerWebExchange ex = mockExchange(
				MockServerHttpRequest.get("/api/device/list").header("Authorization", "Bearer " + token).build(),
				response);
		CapturingChain chain = new CapturingChain();
		filter.filter(ex, chain).block();

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		assertTrue(response.getBodyAsString().block().contains("40101"));
		assertEquals(0, chain.forwarded.size());
	}

}
