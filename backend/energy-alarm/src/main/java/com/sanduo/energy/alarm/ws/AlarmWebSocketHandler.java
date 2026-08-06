package com.sanduo.energy.alarm.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 实时告警推送（/ws/alarm）。
 *
 * <p>驾驶舱/运营大屏订阅后接收告警事件 JSON（与 Kafka iot-alarm 载荷一致）。
 * 会话集合 CopyOnWriteArraySet 线程安全；广播失败仅记录，不影响告警主链路。</p>
 */
@Slf4j
@Component
public class AlarmWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        // 登录身份由 WsAuthInterceptor 握手时写入 attributes（P0-2 鉴权）
        Object userId = session.getAttributes().get(WsAuthInterceptor.ATTR_USER_ID);
        Object tenantId = session.getAttributes().get(WsAuthInterceptor.ATTR_TENANT_ID);
        log.info("[Alarm] WS 连接建立 session={} userId={} tenantId={} 当前在线={}",
                session.getId(), userId, tenantId, sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[Alarm] WS 连接关闭 session={} 当前在线={}", session.getId(), sessions.size());
    }

    /** 向全部在线会话广播告警 JSON */
    public void broadcast(String json) {
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            } catch (Exception e) {
                log.debug("[Alarm] WS 广播失败 session={}", session.getId(), e);
                sessions.remove(session);
            }
        }
    }
}
