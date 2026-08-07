package com.energyx.alarm.ws;

import com.energyx.security.JwtClaims;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS 握手鉴权（P0-2）：有效 token 放行并写入 attributes；缺 token / 错密钥 / 过期 → 401 拒绝。
 */
class WsAuthInterceptorTest {

    private WsAuthInterceptor interceptor;
    private JwtProperties props;
    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private Map<String, Object> attributes;

    private static final String SECRET = "test-secret-key-0123456789abcdefghijklmnopqrstuv";
    private static final JwtClaims ADMIN =
            new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", "test-sid");

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer("energyx-ems");
        interceptor = new WsAuthInterceptor(props);
        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
        attributes = new HashMap<>();
    }

    @Test
    void validToken_allowed_attributesFilled() {
        String token = JwtTokenUtil.sign(props, ADMIN);
        when(request.getURI()).thenReturn(URI.create("ws://127.0.0.1:8115/ws/alarm?token=" + token));

        boolean allowed = interceptor.beforeHandshake(request, response, null, attributes);

        assertTrue(allowed);
        assertEquals(1L, attributes.get(WsAuthInterceptor.ATTR_USER_ID));
        assertEquals("admin", attributes.get(WsAuthInterceptor.ATTR_USERNAME));
        assertEquals(1L, attributes.get(WsAuthInterceptor.ATTR_TENANT_ID));
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void missingToken_rejected_401() {
        when(request.getURI()).thenReturn(URI.create("ws://127.0.0.1:8115/ws/alarm"));

        boolean allowed = interceptor.beforeHandshake(request, response, null, attributes);

        assertFalse(allowed);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertTrue(attributes.isEmpty());
    }

    @Test
    void wrongSecretToken_rejected_401() {
        JwtProperties other = new JwtProperties();
        other.setSecret("another-dev-secret-0123456789abcdefghijklmno");
        String token = JwtTokenUtil.sign(other, ADMIN);
        when(request.getURI()).thenReturn(URI.create("ws://127.0.0.1:8115/ws/alarm?token=" + token));

        boolean allowed = interceptor.beforeHandshake(request, response, null, attributes);

        assertFalse(allowed);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredToken_rejected_401() {
        JwtProperties expired = new JwtProperties();
        expired.setSecret(SECRET);
        expired.setIssuer("energyx-ems");
        expired.setExpireSeconds(-1); // 过期时间落在过去，签出即过期
        String token = JwtTokenUtil.sign(expired, ADMIN);
        when(request.getURI()).thenReturn(URI.create("ws://127.0.0.1:8115/ws/alarm?token=" + token));

        boolean allowed = interceptor.beforeHandshake(request, response, null, attributes);

        assertFalse(allowed);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
