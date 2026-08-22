package com.energyx.broker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub 消息监听容器（P2-6 凭据失效广播等场景）。
 *
 * <p>
 * 使用 Spring Data Redis 标准容器而非手动 {@code RedisConnection.subscribe}： Lettuce 下手动 subscribe
 * 依赖专用 pub/sub 连接，经 getConnection() 拿到的连接行为不可靠； 容器内部维护专用 pub/sub 连接并处理重连，生产更稳。
 * </p>
 */
@Configuration
public class RedisPubSubConfig {

	/**
	 * Redis 消息监听容器 Bean：托管 pub/sub 专用连接与自动重连（凭据失效广播等场景使用）。
	 * <p>
	 * 选用 Spring 容器而非手动 subscribe，规避 Lettuce 下手动拿 pub/sub 连接行为不可靠；容器负责维护专用连接并处理断线重连。
	 * </p>
	 * @param connectionFactory Redis 连接工厂
	 * @return 已配置连接工厂的监听容器
	 */
	@Bean(destroyMethod = "stop")
	public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		return container;
	}

}
