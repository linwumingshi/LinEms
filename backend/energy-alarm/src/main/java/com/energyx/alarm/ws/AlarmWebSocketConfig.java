package com.energyx.alarm.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配：/ws/alarm 实时告警推送。
 *
 * <p>网关 GlobalAuthFilter 对 /ws 前缀只放行升级请求，JWT 验签下沉到
 * {@link WsAuthInterceptor}（握手时校验 URL 查询参数 token，见 P0-2）；
 * 直连本服务绕过网关的路径同样被拒。此处允许跨域以兼容本地前后端联调。</p>
 */
@Configuration
@EnableWebSocket
public class AlarmWebSocketConfig implements WebSocketConfigurer {

    private final AlarmWebSocketHandler alarmWebSocketHandler;
    private final WsAuthInterceptor wsAuthInterceptor;

    public AlarmWebSocketConfig(AlarmWebSocketHandler alarmWebSocketHandler,
                                WsAuthInterceptor wsAuthInterceptor) {
        this.alarmWebSocketHandler = alarmWebSocketHandler;
        this.wsAuthInterceptor = wsAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alarmWebSocketHandler, "/ws/alarm")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");
    }
}
