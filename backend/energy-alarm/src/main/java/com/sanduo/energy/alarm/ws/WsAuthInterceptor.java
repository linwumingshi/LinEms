package com.sanduo.energy.alarm.ws;

import com.sanduo.energy.security.JwtClaims;
import com.sanduo.energy.security.JwtProperties;
import com.sanduo.energy.security.JwtTokenException;
import com.sanduo.energy.security.JwtTokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * /ws/alarm 握手鉴权（P0-2）：浏览器原生 WebSocket 无法携带 Authorization 头，
 * token 经 URL 查询参数传递（{@code ws://host/ws/alarm?token=&lt;jwt&gt;}）。
 *
 * <p>网关 GlobalAuthFilter 对 /ws 前缀只放行升级请求，真正的 JWT 验签下沉到这里：
 * 验签通过 → 把 userId/username/tenantId 写入握手 attributes（供
 * {@link AlarmWebSocketHandler#afterConnectionEstablished} 记录登录身份）；
 * 缺省/伪造/过期 → 401 拒绝握手。直连本服务绕过网关的路径同样被拒（防御纵深）。</p>
 */
@Slf4j
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    /** attributes 中的用户 ID 键 */
    public static final String ATTR_USER_ID = "ws.userId";
    /** attributes 中的用户名键 */
    public static final String ATTR_USERNAME = "ws.username";
    /** attributes 中的租户 ID 键 */
    public static final String ATTR_TENANT_ID = "ws.tenantId";

    private static final String TOKEN_PARAM = "token";

    private final JwtProperties jwtProperties;

    public WsAuthInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst(TOKEN_PARAM);
        if (token == null || token.isBlank()) {
            log.warn("[Alarm] WS 握手拒绝：缺少 token，query={}", request.getURI().getRawQuery());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            JwtClaims claims = JwtTokenUtil.parse(jwtProperties, token);
            attributes.put(ATTR_USER_ID, claims.userId());
            attributes.put(ATTR_USERNAME, claims.username());
            attributes.put(ATTR_TENANT_ID, claims.tenantId());
            return true;
        } catch (JwtTokenException e) {
            log.warn("[Alarm] WS 握手拒绝：token 无效 reason={}", e.getReason());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需处理
    }
}
