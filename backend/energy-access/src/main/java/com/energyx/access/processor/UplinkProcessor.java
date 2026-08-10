package com.energyx.access.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.device.DeviceInfo;
import com.energyx.access.device.DeviceInfoCache;
import com.energyx.access.model.ModelValidator;
import com.energyx.access.model.ThingModel;
import com.energyx.access.model.ThingModelCache;
import com.energyx.access.publish.EventPublisher;
import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.message.RawMessage;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.mqtt.MqttTopicInfo;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.MqttUpType;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.energyx.common.redis.MessageDedup;
import com.energyx.common.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备上行报文处理器（消费 mqtt.router 的 PUBLISH 信封）。
 *
 * <p>
 * 流水线（Phase 1 §6.1 / Phase 4 §1.2）： <pre>
 * 信封 → KICK/下行忽略 → parseUpTopic → 设备上下文（cache:device）
 *      → messageId（设备带或生成）→ 幂等去重（access 边界）
 *      → iot-raw 原始留痕（无条件）→ 按 upType 分流：
 *          property → 物模型校验/强转 → iot-thing-property
 *          event    → 事件白名单/级别映射 → iot-thing-event
 *          ack      → 指令应答 → iot-command-ack
 *          lifecycle→ 设备自报上下线 → iot-device-lifecycle
 * </pre>
 * </p>
 *
 * <p>
 * 线程安全：无实例状态（所有依赖为线程安全 Bean），可供 KafkaConsumerEngine 多线程并发调用。
 * </p>
 */
@Slf4j
@Component
public class UplinkProcessor implements KafkaRecordHandler {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {
	};

	private final EventPublisher publisher;

	private final ThingModelCache modelCache;

	private final DeviceInfoCache deviceInfoCache;

	private final MessageDedup messageDedup;

	private final AccessProperties props;

	private final ObjectMapper objectMapper;

	private final SnowflakeIdGenerator idGenerator;

	public UplinkProcessor(EventPublisher publisher, ThingModelCache modelCache, DeviceInfoCache deviceInfoCache,
			MessageDedup messageDedup, AccessProperties props, ObjectMapper objectMapper,
			SnowflakeIdGenerator idGenerator) {
		this.publisher = publisher;
		this.modelCache = modelCache;
		this.deviceInfoCache = deviceInfoCache;
		this.messageDedup = messageDedup;
		this.props = props;
		this.objectMapper = objectMapper;
		this.idGenerator = idGenerator;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		RouterEnvelope envelope;
		try {
			// 阶段 2：二进制信封（magic 0xE9 0x01）；StringDeserializer 读回的 String 按
			// ISO-8859-1 还原字节无损（ByteArraySerializer 写出的原始字节）。兼容期 JSON 自动探测。
			byte[] raw = record.value().getBytes(StandardCharsets.ISO_8859_1);
			envelope = RouterEnvelopeCodec.decode(raw, objectMapper);
		}
		catch (Exception e) {
			log.warn("[Access] mqtt.uplink 信封解码失败 key={} offset={}，丢弃", record.key(), record.offset());
			return;
		}
		if (!RouterEnvelope.TYPE_PUBLISH.equals(envelope.getType())) {
			return; // KICK 信封（跨节点踢线），接入侧不关注
		}
		MqttTopicInfo info = MqttTopicUtil.parseUpTopic(envelope.getTopic());
		if (info == null) {
			return; // down/* 或非法格式，非设备上行
		}
		byte[] payload = envelope.decodePayload();
		String deviceKey = MqttTopicUtil.buildDeviceKey(info.productKey(), info.deviceName());
		DeviceInfo device = deviceInfoCache.get(info.productKey(), info.deviceName());
		String messageId = extractMessageId(payload);

		if (device == null) {
			traceRaw(messageId, deviceKey, null, envelope, "DEVICE_NOT_FOUND");
			return;
		}
		// 幂等去重（access 边界）：设备重发/消费重放在此拦截，raw 不重复留痕
		if (!messageDedup.tryOnce("access", device.getDeviceId(), messageId, props.getMsgDedupTtlSeconds())) {
			return;
		}
		traceRaw(messageId, deviceKey, device.getDeviceId(), envelope, null);

		switch (info.upType()) {
			case PROPERTY -> processProperty(device, info, payload, messageId);
			case EVENT -> processEvent(device, info, payload, messageId);
			case ACK -> processAck(device, payload, messageId);
			case LIFECYCLE -> processDeviceLifecycle(device, info, payload, messageId);
		}
	}

	private void processProperty(DeviceInfo device, MqttTopicInfo info, byte[] payload, String messageId) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			String dataType = root.path("dataType").asText("report");
			long ts = root.path("ts").asLong(System.currentTimeMillis());
			Map<String, Object> reported = objectMapper.convertValue(root.path("properties"), MAP_TYPE);

			ThingModel model = modelCache.get(info.productKey());
			if (model == null) {
				log.warn("[Access] 物模型缺失 productKey={} deviceId={}，跳过属性标准化", info.productKey(), device.getDeviceId());
				return;
			}
			ModelValidator.ValidationResult result = ModelValidator.validateProperties(model, reported);
			if (!result.valid()) {
				log.warn("[Access] 属性校验拒绝 deviceId={} messageId={} errors={}", device.getDeviceId(), messageId,
						result.errors());
				return;
			}
			ThingPropertyMessage msg = new ThingPropertyMessage();
			msg.setMessageId(messageId);
			msg.setDeviceId(device.getDeviceId());
			msg.setTenantId(device.getTenantId());
			msg.setEnterpriseId(device.getEnterpriseId());
			msg.setStationId(device.getStationId());
			msg.setProductKey(info.productKey());
			msg.setDataType(dataType);
			msg.setTs(ts);
			msg.setProperties(result.coerced());
			publisher.publishProperty(msg);
		}
		catch (Exception e) {
			log.warn("[Access] 属性报文处理失败 deviceId={} messageId={}", device.getDeviceId(), messageId, e);
		}
	}

	private void processEvent(DeviceInfo device, MqttTopicInfo info, byte[] payload, String messageId) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			String eventName = root.path("eventName").asText("");
			if (eventName.isEmpty()) {
				log.warn("[Access] 事件缺 eventName deviceId={}", device.getDeviceId());
				return;
			}
			ThingModel model = modelCache.get(info.productKey());
			if (model == null) {
				log.warn("[Access] 物模型缺失 productKey={} deviceId={}，跳过事件标准化", info.productKey(), device.getDeviceId());
				return;
			}
			ModelValidator.EventCheck check = ModelValidator.checkEvent(model, eventName);
			if (check == null) {
				log.warn("[Access] 未知事件 eventName={} deviceId={}（未在物模型登记）", eventName, device.getDeviceId());
				return;
			}
			ThingEventMessage msg = new ThingEventMessage();
			msg.setMessageId(messageId);
			msg.setEventId(root.path("eventId").asText(idGenerator.nextIdStr()));
			msg.setDeviceId(device.getDeviceId());
			msg.setTenantId(device.getTenantId());
			msg.setEnterpriseId(device.getEnterpriseId());
			msg.setStationId(device.getStationId());
			msg.setProductKey(info.productKey());
			msg.setEventName(eventName);
			msg.setSeverity(root.path("severity").asInt(check.severity()));
			msg.setCode(root.path("code").asText(null));
			msg.setTs(root.path("ts").asLong(System.currentTimeMillis()));
			msg.setData(objectMapper.convertValue(root.path("data"), MAP_TYPE));
			publisher.publishEvent(msg);
		}
		catch (Exception e) {
			log.warn("[Access] 事件报文处理失败 deviceId={} messageId={}", device.getDeviceId(), messageId, e);
		}
	}

	private void processAck(DeviceInfo device, byte[] payload, String messageId) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			String commandId = root.path("commandId").asText("");
			if (commandId.isEmpty()) {
				log.warn("[Access] ACK 缺 commandId deviceId={} messageId={}", device.getDeviceId(), messageId);
				return;
			}
			CommandAckMessage ack = new CommandAckMessage();
			ack.setCommandId(commandId);
			ack.setDeviceId(device.getDeviceId());
			ack.setStatus(root.path("status").asText("SUCCESS"));
			ack.setErrorCode(root.path("errorCode").asText(null));
			ack.setResult(objectMapper.convertValue(root.path("result"), MAP_TYPE));
			ack.setTs(root.path("ts").asLong(System.currentTimeMillis()));
			publisher.publishAck(ack);
		}
		catch (Exception e) {
			log.warn("[Access] ACK 报文处理失败 deviceId={} messageId={}", device.getDeviceId(), messageId, e);
		}
	}

	private void processDeviceLifecycle(DeviceInfo device, MqttTopicInfo info, byte[] payload, String messageId) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			String eventType = root.path("eventType").asText("ONLINE").toUpperCase();
			if (!"ONLINE".equals(eventType) && !"OFFLINE".equals(eventType)) {
				eventType = "ONLINE";
			}
			LifecycleMessage lm = new LifecycleMessage();
			lm.setEventType(eventType);
			lm.setDeviceId(device.getDeviceId());
			lm.setTenantId(device.getTenantId());
			lm.setProductKey(info.productKey());
			lm.setDeviceName(info.deviceName());
			lm.setBrokerNode(props.getNodeId());
			lm.setIp(root.path("ip").asText(null));
			lm.setReason("DEVICE_SELF");
			lm.setTs(root.path("ts").asLong(System.currentTimeMillis()));
			publisher.publishLifecycle(lm);
		}
		catch (Exception e) {
			log.warn("[Access] 生命周期自报处理失败 deviceId={} messageId={}", device.getDeviceId(), messageId, e);
		}
	}

	private String extractMessageId(byte[] payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			String id = root.path("messageId").asText("");
			return id.isEmpty() ? idGenerator.nextIdStr() : id;
		}
		catch (Exception e) {
			return idGenerator.nextIdStr();
		}
	}

	private void traceRaw(String messageId, String deviceKey, Long deviceId, RouterEnvelope envelope,
			String rejectReason) {
		RawMessage raw = new RawMessage();
		raw.setMessageId(messageId);
		raw.setDeviceKey(deviceKey);
		raw.setDeviceId(deviceId);
		raw.setTopic(envelope.getTopic());
		raw.setQos(envelope.getQos());
		raw.setRetain(envelope.isRetain());
		raw.setTs(envelope.getTs());
		raw.setPayloadBase64(envelope.getPayloadBase64());
		raw.setRejectReason(rejectReason);
		publisher.publishRaw(raw);
	}

}
