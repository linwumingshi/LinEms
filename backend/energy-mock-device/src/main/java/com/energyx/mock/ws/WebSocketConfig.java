package com.energyx.mock.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：/ws/mock 端点挂 {@link MockWebSocketHandler}。 允许跨域（dev 前端 5173 经 vite
 * 代理同源，生产经网关）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final MockWebSocketHandler handler;

	public WebSocketConfig(MockWebSocketHandler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws/mock").setAllowedOrigins("*");
	}

}
