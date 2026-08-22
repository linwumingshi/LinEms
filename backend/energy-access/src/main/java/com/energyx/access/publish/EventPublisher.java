package com.energyx.access.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.device.BrokerNodeResolver;
import com.energyx.access.mqtt.AccessKafkaProducer;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.CommandDownMessage;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.message.OtaUpMessage;
import com.energyx.common.message.RawMessage;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化消息出口（唯一入口点，统一序列化与发送）。
 *
 * <p>
 * Key 策略对齐 Phase 1 §7.2 顺序保证：
 * <ul>
 * <li>property/event/lifecycle 以 deviceId 为 key ⇒ 单设备有序、按设备分区；</li>
 * <li>ack 以 commandId 为 key ⇒ 指令 ACK 与指令记录同分区保序；</li>
 * <li>raw 以 messageId 为 key；下行信封以 deviceKey 为 key（阶段 2：定向 topic 分区有序）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class EventPublisher {

	private final AccessKafkaProducer producer;

	private final AccessProperties props;

	private final BrokerNodeResolver nodeResolver;

	private final ObjectMapper objectMapper;

	public EventPublisher(AccessKafkaProducer producer, AccessProperties props, BrokerNodeResolver nodeResolver,
			ObjectMapper objectMapper) {
		this.producer = producer;
		this.props = props;
		this.nodeResolver = nodeResolver;
		this.objectMapper = objectMapper;
	}

	/** 发布原始报文留痕 iot-raw（key=messageId） */
	public void publishRaw(RawMessage m) {
		send(KafkaTopicConstant.IOT_RAW, m.getMessageId(), m);
	}

	/** 发布属性标准化消息 iot-thing-property（key=deviceId） */
	public void publishProperty(ThingPropertyMessage m) {
		send(KafkaTopicConstant.IOT_THING_PROPERTY, String.valueOf(m.getDeviceId()), m);
	}

	/** 发布事件标准化消息 iot-thing-event（key=deviceId） */
	public void publishEvent(ThingEventMessage m) {
		send(KafkaTopicConstant.IOT_THING_EVENT, String.valueOf(m.getDeviceId()), m);
	}

	/** 发布指令应答 iot-command-ack（key=commandId，与指令记录同分区保序） */
	public void publishAck(CommandAckMessage m) {
		send(KafkaTopicConstant.IOT_COMMAND_ACK, m.getCommandId(), m);
	}

	/** 发布设备生命周期 iot-device-lifecycle（key=deviceId） */
	public void publishLifecycle(LifecycleMessage m) {
		send(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, String.valueOf(m.getDeviceId()), m);
	}

	/** 发布平台下行指令 iot-command-down（key=deviceId） */
	public void publishCommandDown(CommandDownMessage m) {
		send(KafkaTopicConstant.IOT_COMMAND_DOWN, String.valueOf(m.getDeviceId()), m);
	}

	/**
	 * 设备 OTA 报文透传（上行）：把 OTA 命名空间报文转发到 ota.uplink 供 OTA 中心消费。 不进入物模型标准化链路，仅携带设备上下文原样透传。
	 */
	public void publishOta(OtaUpMessage m) {
		send(KafkaTopicConstant.OTA_UPLINK, String.valueOf(m.getDeviceId()), m);
	}

	/**
	 * 平台下行：把 CommandDownMessage 桥接为 PUBLISH 信封（阶段 2 定向投递）。
	 *
	 * <p>
	 * 按设备连接锁（mqtt:conn:{deviceKey}）解析所在 Broker 节点： 命中 → 定向投递 mqtt.down.{nodeId}（仅目标节点消费，无
	 * fan-out）； 未命中（离线/竞态）→ 回落 mqtt.broadcast，由节点幽灵订阅入离线队列 / 上线接管兜底。
	 * 信封为二进制（RouterEnvelopeCodec），access 无需直连 Broker。
	 * </p>
	 */
	public void publishRouterDown(CommandDownMessage m) {
		String topic = MqttTopicUtil.downCommandTopic(m.getProductKey(), m.getDeviceName());
		String deviceKey = MqttTopicUtil.buildDeviceKey(m.getProductKey(), m.getDeviceName());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("commandId", m.getCommandId());
		body.put("command", m.getCommand());
		body.put("params", m.getParams() == null ? Collections.emptyMap() : m.getParams());
		body.put("ts", m.getTs() == null ? System.currentTimeMillis() : m.getTs());
		try {
			byte[] payload = objectMapper.writeValueAsBytes(body);
			RouterEnvelope env = RouterEnvelope.publish(props.getNodeId(), topic, payload,
					m.getQos() == null ? 1 : m.getQos(), false);
			byte[] envelope = RouterEnvelopeCodec.encode(env);
			String owner = nodeResolver.resolveNode(deviceKey);
			if (owner != null && !owner.isBlank()) {
				producer.sendBytes(KafkaTopicConstant.MQTT_DOWN_PREFIX + owner, deviceKey, envelope);
			}
			else {
				// 离线/竞态：广播回落（Broker 幽灵订阅 / 上线接管兜底）
				log.debug("[Access] 下行无 owner，回落广播 deviceKey={} topic={}", deviceKey, topic);
				producer.sendBytes(KafkaTopicConstant.MQTT_BROADCAST, deviceKey, envelope);
			}
		}
		catch (Exception e) {
			log.error("[Access] 下行信封序列化失败 commandId={}", m.getCommandId(), e);
		}
	}

	/**
	 * 统一序列化发送：JSON 序列化后交 AccessKafkaProducer 异步发送，失败仅记日志不阻塞主流程。
	 * @param topic 目标 topic
	 * @param key 分区 key（保证同设备/同指令有序）
	 * @param value 消息对象
	 */
	private void send(String topic, String key, Object value) {
		try {
			producer.send(topic, key, objectMapper.writeValueAsString(value));
		}
		catch (Exception e) {
			log.error("[Access] 消息序列化失败 topic={} key={}", topic, key, e);
		}
	}

}