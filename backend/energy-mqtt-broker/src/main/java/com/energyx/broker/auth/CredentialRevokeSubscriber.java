package com.energyx.broker.auth;

import com.energyx.broker.session.Session;
import com.energyx.broker.session.SessionRegistry;
import com.energyx.broker.session.SessionStore;
import com.energyx.broker.util.BrokerKeys;
import com.energyx.common.redis.RedisChannelConstant;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 凭据失效广播订阅端（P2-6）：订阅 Redis 通道 {@code mqtt:cred:revoked}。
 *
 * <p>
 * 设备服务在吊销/禁用/重置凭据时 PUBLISH clientId 到该通道，Broker 收到后：
 * <ol>
 * <li>删除 Redis 凭据缓存 cache:cred:{clientId}（吊销从最长 30min 缩到秒级生效）；</li>
 * <li>关闭该设备的在线连接，强制重新认证（新凭据生效、旧凭据被拒）。</li>
 * </ol>
 * 基于 {@link RedisMessageListenerContainer}（专用 pub/sub 连接 + 断线重连）。
 * </p>
 *
 * <p>
 * 发布方（设备服务侧，示意）： {@code redisTemplate.convertAndSend("mqtt:cred:revoked", clientId)}。
 * </p>
 */
@Slf4j
@Component
public class CredentialRevokeSubscriber {

	/** 凭据失效广播通道（共享常量，与 device 侧发布端一致；已补登 Redis-key 规范 §3.9） */
	public static final String CHANNEL = RedisChannelConstant.CREDENTIAL_REVOKED;

	private final RedisMessageListenerContainer container;

	private final SessionStore sessionStore;

	private final SessionRegistry sessionRegistry;

	/** 注入依赖并初始化凭据失效订阅端：Redis 订阅容器、会话存储、会话注册表 */
	public CredentialRevokeSubscriber(RedisMessageListenerContainer container, SessionStore sessionStore,
			SessionRegistry sessionRegistry) {
		this.container = container;
		this.sessionStore = sessionStore;
		this.sessionRegistry = sessionRegistry;
	}

	/**
	 * 注册凭据失效广播监听器并启动订阅容器，开始监听吊销通道
	 */
	@PostConstruct
	public void start() {
		container.addMessageListener(
				(message, pattern) -> handleRevoke(new String(message.getBody(), StandardCharsets.UTF_8)),
				new ChannelTopic(CHANNEL));
		container.start();
		log.info("[Auth] 凭据失效广播订阅启动 channel={}", CHANNEL);
	}

	/** 处理凭据失效广播：删除凭据缓存并强制踢掉该设备的在线连接 */
	private void handleRevoke(String clientId) {
		// 1. 删除凭据缓存，下一次认证强制回源 MySQL 拿到最新状态
		sessionStore.delete(BrokerKeys.credentialCache(clientId));
		// 2. 踢在线连接，强制重新认证（旧凭据立即失效）
		Session session = sessionRegistry.get(clientId);
		if (session != null) {
			log.warn("[Auth] 凭据已吊销，强制踢线 deviceKey={}", clientId);
			session.getChannel().close();
		}
		else {
			log.info("[Auth] 凭据已吊销 clientId={}（设备不在线）", clientId);
		}
	}

	/**
	 * 销毁钩子：订阅容器由 RedisPubSubConfig 负责停止，这里仅作占位
	 */
	@PreDestroy
	public void stop() {
		// 容器由 RedisPubSubConfig 的 destroyMethod 负责停止
	}

}
