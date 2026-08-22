package com.energyx.access.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.publish.EventPublisher;
import com.energyx.access.util.AccessKeys;
import com.energyx.common.message.CommandDownMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 离线指令补发（设备上线触发）：把 Redis 离线队列 iot:cmd:q:{deviceId} 中的 CommandDownMessage 按 FIFO
 * 逐条重新桥接为下行（复用 CommandDownConsumer 的下发路径）。
 *
 * <p>
 * 队列由 Phase 6 Command Center 在「设备离线时下发指令」写入；补发后指令状态机的 最终一致性（重试/超时/失败）由 Phase 6 command
 * 模块在 iot_command 权威表中收敛。 解析失败的脏数据仅记日志（权威表可重建），不阻塞队列消费。
 * </p>
 */
@Slf4j
@Component
public class OfflineCommandRedeliverer {

	private final StringRedisTemplate redis;

	private final EventPublisher publisher;

	private final AccessProperties props;

	private final ObjectMapper objectMapper;

	public OfflineCommandRedeliverer(StringRedisTemplate redis, EventPublisher publisher, AccessProperties props,
			ObjectMapper objectMapper) {
		this.redis = redis;
		this.publisher = publisher;
		this.props = props;
		this.objectMapper = objectMapper;
	}

	/**
	 * 设备上线补发离线指令：按 FIFO 从 Redis 离线队列取出 CommandDownMessage 重新桥接下行，直到达上限或队列空。
	 * @param deviceId 设备 ID
	 */
	public void redeliver(long deviceId) {
		String key = AccessKeys.cmdQueue(deviceId);
		int sent = 0;
		String json;
		// 按 FIFO 从离线队列左弹出，逐条桥接下行，受上限保护避免单设备风暴
		while (sent < props.getOfflineMaxRedeliver() && (json = redis.opsForList().leftPop(key)) != null) {
			try {
				CommandDownMessage cmd = objectMapper.readValue(json, CommandDownMessage.class);
				publisher.publishRouterDown(cmd);
				sent++;
			}
			catch (Exception e) {
				log.error("[Access] 离线指令补发解析失败 deviceId={} json={}", deviceId, json, e);
			}
		}
		if (sent > 0) {
			log.info("[Access] 设备上线补发离线指令 deviceId={} count={}", deviceId, sent);
		}
	}

}
