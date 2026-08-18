package com.energyx.mock.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 模拟设备 WebSocket 处理器（/ws/mock）：把设备下行（命令/OTA/日志）实时推给前端。
 *
 * <p>
 * v1 为单向（服务端→前端广播）；前端→服务端走 REST 控制面（创建/上报/应答）。 会话管理用线程安全集合，广播时跳过已关闭会话。
 * </p>
 */
@Slf4j
@Component
public class MockWebSocketHandler extends TextWebSocketHandler implements WsBroadcaster {

	private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		sessions.add(session);
		log.info("[MOCK-WS] 前端接入，当前会话数={}", sessions.size());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
		sessions.remove(session);
		log.info("[MOCK-WS] 前端断开，当前会话数={}", sessions.size());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		// v1 单向推送，前端上行暂不处理
	}

	@Override
	public void broadcast(String json) {
		for (WebSocketSession session : sessions) {
			if (session.isOpen()) {
				try {
					session.sendMessage(new TextMessage(json));
				}
				catch (IOException e) {
					log.warn("[MOCK-WS] 推送失败，移除会话: {}", e.getMessage());
					sessions.remove(session);
				}
			}
		}
	}

}
