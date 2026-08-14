package com.energyx.rule.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub 消息监听容器（rule:changed 规则热更新广播，Phase 11 §6）。
 *
 * <p>
 * 使用 Spring Data Redis 标准容器而非手动 {@code RedisConnection.subscribe}： Lettuce 下手动 subscribe
 * 依赖专用 pub/sub 连接，经 getConnection() 拿到的连接行为不可靠； 容器内部维护专用 pub/sub 连接并处理重连，生产更稳（同 broker
 * RedisPubSubConfig 模式）。
 * </p>
 */
@Configuration
public class RedisPubSubConfig {

	@Bean(destroyMethod = "stop")
	public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		return container;
	}

}
