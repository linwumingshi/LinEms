package com.energyx.access.device;

import com.energyx.access.util.AccessKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BrokerNodeResolverTest {

	private StringRedisTemplate redis;

	private ValueOperations<String, String> values;

	private BrokerNodeResolver resolver;

	@BeforeEach
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		resolver = new BrokerNodeResolver(redis);
	}

	@Test
	void resolveNode_ownerAlive_returnsOwner() {
		when(values.get(AccessKeys.brokerConnLock("dev"))).thenReturn("broker-1");
		when(redis.hasKey(AccessKeys.nodeHeartbeat("broker-1"))).thenReturn(true);
		assertEquals("broker-1", resolver.resolveNode("dev"));
	}

	@Test
	void resolveNode_ownerDead_returnsNull() {
		// owner 锁还在但心跳 key 已消失 → 判定死节点，回落广播/离线队列
		when(values.get(AccessKeys.brokerConnLock("dev"))).thenReturn("broker-2");
		when(redis.hasKey(AccessKeys.nodeHeartbeat("broker-2"))).thenReturn(false);
		assertNull(resolver.resolveNode("dev"));
	}

	@Test
	void resolveNode_noOwner_returnsNull() {
		when(values.get(AccessKeys.brokerConnLock("dev"))).thenReturn(null);
		assertNull(resolver.resolveNode("dev"));
	}

	@Test
	void resolveNode_redisError_returnsNull() {
		// Redis 异常 → 保守回落广播，不抛异常阻断下行
		when(values.get(AccessKeys.brokerConnLock("dev"))).thenThrow(new RuntimeException("redis down"));
		assertNull(resolver.resolveNode("dev"));
	}

	@Test
	void resolveNode_heartbeatCheckError_returnsOwner() {
		// 心跳判定异常（如 hasKey 抛错）→ 保守返回 owner，不因新功能阻断既有定向
		when(values.get(AccessKeys.brokerConnLock("dev"))).thenReturn("broker-1");
		when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
		assertEquals("broker-1", resolver.resolveNode("dev"));
	}

}
