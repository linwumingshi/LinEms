package com.energyx.access.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.energyx.access.config.AccessProperties;
import com.energyx.access.device.DeviceInfo;
import com.energyx.access.device.DeviceInfoCache;
import com.energyx.access.model.ThingModelCache;
import com.energyx.access.publish.EventPublisher;
import com.energyx.common.kafka.BytesKafkaRecordHandler;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.message.OtaUpMessage;
import com.energyx.common.message.RawMessage;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import com.energyx.common.mqtt.MqttTopicInfo;
import com.energyx.common.mqtt.MqttTopicUtil;
import com.energyx.common.mqtt.MqttUpType;
import com.energyx.common.mqtt.RouterEnvelope;
import com.energyx.common.mqtt.RouterEnvelopeCodec;
import com.energyx.common.redis.MessageDedup;
import com.energyx.common.thingmodel.ModelValidator;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 设备上行报文处理器（消费 mqtt.uplink 的二进制信封）。
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
public class UplinkProcessor implements BytesKafkaRecordHandler {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {
	};

	/** OTA 命名空间子类型（对应设备 topic {pk}/{dn}/ota/{type}） */
	private static final Set<String> OTA_TYPES = Set.of("inform", "progress", "result", "pull");

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

	/**
	 * 消费单条上行报文：解码信封 → 解析上行 topic → 设备上下文 → 去重 → 按 upType 分流处理。
	 * @param record Kafka 消费者记录（value 为二进制信封）
	 * @throws Exception 解码或处理过程中的异常（由消费引擎捕获并走 DLQ）
	 */
	@Override
	public void handle(ConsumerRecord<String, byte[]> record) throws Exception {
		RouterEnvelope envelope;
		try {
			// 阶段 2：二进制信封（magic 0xE9 0x01）。消费端 ByteArrayDeserializer 保字节无损；
			// 兼容期 JSON 自动探测（RouterEnvelopeCodec.decode 按 magic 自识别）。
			byte[] raw = record.value();
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
			// OTA 命名空间（{pk}/{dn}/ota/{type}）透传：不进入物模型链路，直接转发 OTA 中心
			if (processOta(envelope)) {
				return;
			}
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

		// 按上行类型分流：property/event/ack/lifecycle 各自标准化后发布到对应 topic
		switch (info.upType()) {
			case PROPERTY -> processProperty(device, info, payload, messageId);
			case EVENT -> processEvent(device, info, payload, messageId);
			case ACK -> processAck(device, payload, messageId);
			case LIFECYCLE -> processDeviceLifecycle(device, info, payload, messageId);
		}
	}

	/**
	 * OTA 命名空间透传：识别 {@code {pk}/{dn}/ota/{inform|progress|result|pull}} 报文， 查设备上下文后原样转发
	 * ota.uplink（不进入物模型校验/标准化链路）。
	 * @return true=已作为 OTA 报文处理；false=非 OTA topic（down/* 或非法格式）
	 */
	private boolean processOta(RouterEnvelope envelope) {
		String topic = envelope.getTopic();
		if (topic == null || topic.isBlank()) {
			return false;
		}
		String[] parts = topic.split("/");
		if (parts.length != 4 || !"ota".equals(parts[2])) {
			return false;
		}
		String otaType = parts[3];
		if (!OTA_TYPES.contains(otaType)) {
			return false;
		}
		DeviceInfo device = deviceInfoCache.get(parts[0], parts[1]);
		if (device == null) {
			log.warn("[Access] OTA 报文设备不存在 productKey={} deviceName={}，丢弃", parts[0], parts[1]);
			return true;
		}
		try {
			OtaUpMessage msg = new OtaUpMessage();
			msg.setDeviceId(device.getDeviceId());
			msg.setTenantId(device.getTenantId());
			msg.setProductKey(parts[0]);
			msg.setDeviceName(parts[1]);
			msg.setOtaType(otaType);
			msg.setTopic(topic);
			msg.setPayload(new String(envelope.decodePayload(), StandardCharsets.UTF_8));
			publisher.publishOta(msg);
		}
		catch (Exception e) {
			log.error("[Access] OTA 报文透传失败 topic={}", topic, e);
		}
		return true;
	}

	/**
	 * 处理属性上报：物模型校验 + 强转后标准化发布 iot-thing-property。
	 * @param device 设备上下文
	 * @param info 已解析的上行 topic 信息
	 * @param payload 报文负载（JSON 字节）
	 * @param messageId 消息唯一 ID（用于链路追踪）
	 */
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

	/**
	 * 处理事件上报：事件白名单校验 + 级别映射后标准化发布 iot-thing-event。
	 * @param device 设备上下文
	 * @param info 已解析的上行 topic 信息
	 * @param payload 报文负载（JSON 字节）
	 * @param messageId 消息唯一 ID（用于链路追踪）
	 */
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

	/**
	 * 处理指令应答：解析 commandId/status/result 后标准化发布 iot-command-ack。
	 * @param device 设备上下文
	 * @param payload 报文负载（JSON 字节）
	 * @param messageId 消息唯一 ID（用于链路追踪）
	 */
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

	/**
	 * 处理设备自报生命周期（ONLINE/OFFLINE）：标准化发布 iot-device-lifecycle，由 access 落库。
	 * @param device 设备上下文
	 * @param info 已解析的上行 topic 信息
	 * @param payload 报文负载（JSON 字节）
	 * @param messageId 消息唯一 ID（用于链路追踪）
	 */
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

	/**
	 * 提取报文 messageId；设备未带则生成雪花 ID（保证幂等键稳定）。
	 * @param payload 报文负载（JSON 字节）
	 * @return 报文 messageId（设备带则用之，否则生成新 ID）
	 */
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

	/**
	 * 原始报文留痕：无条件写 iot-raw（追踪/补数），rejectReason 非空表示被拒绝的报文。
	 * @param messageId 消息唯一 ID
	 * @param deviceKey 设备键（productKey_deviceName）
	 * @param deviceId 设备 ID（未识别时为 null）
	 * @param envelope 原始信封（含 topic/qos/payload）
	 * @param rejectReason 拒绝原因（null 表示正常留痕）
	 */
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
