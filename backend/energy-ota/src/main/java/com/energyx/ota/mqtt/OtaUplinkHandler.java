package com.energyx.ota.mqtt;

import com.energyx.ota.service.OtaVersionCache;
import com.energyx.common.message.OtaUpMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.kafka.KafkaRecordHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 设备 OTA 上行报文处理器（消费 ota.uplink，key=deviceId 保证单设备保序）。
 *
 * <p>
 * S2 落地 inform 版本缓存；progress/result/pull 在 S3 任务体系就绪后驱动任务状态机， 当前先记录日志与幂等占位。
 * </p>
 */
@Slf4j
@Component
public class OtaUplinkHandler implements KafkaRecordHandler {

	private final OtaVersionCache versionCache;

	private final ObjectMapper objectMapper;

	public OtaUplinkHandler(OtaVersionCache versionCache, ObjectMapper objectMapper) {
		this.versionCache = versionCache;
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(ConsumerRecord<String, String> record) throws Exception {
		OtaUpMessage msg;
		try {
			msg = objectMapper.readValue(record.value(), OtaUpMessage.class);
		}
		catch (Exception e) {
			log.warn("[OTA] ota.uplink 消息反序列化失败 offset={}，丢弃", record.offset());
			return;
		}
		if (msg == null || msg.getDeviceId() == null) {
			return;
		}
		switch (msg.getOtaType() == null ? "" : msg.getOtaType()) {
			case "inform" -> handleInform(msg);
			case "progress" -> handleProgress(msg);
			case "result" -> handleResult(msg);
			case "pull" -> handlePull(msg);
			default -> log.warn("[OTA] 未知 OTA 报文类型 type={} deviceId={}", msg.getOtaType(), msg.getDeviceId());
		}
	}

	/**
	 * 版本上报：解析当前固件版本并写入 Redis 缓存（升级成功判定的版本来源）。
	 */
	private void handleInform(OtaUpMessage msg) {
		try {
			JsonNode root = objectMapper.readTree(msg.getPayload());
			String version = root.path("version").asText(null);
			String module = root.path("module").asText("main");
			if (version == null || version.isBlank()) {
				log.warn("[OTA] inform 缺版本字段 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload());
				return;
			}
			versionCache.setVersion(msg.getDeviceId(), version, module);
			log.info("[OTA] 设备版本上报 deviceId={} version={} module={}", msg.getDeviceId(), version, module);
		}
		catch (Exception e) {
			log.warn("[OTA] inform 解析失败 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload(), e);
		}
	}

	/**
	 * 进度上报：任务状态机推进（S3 落地）；当前仅日志。
	 */
	private void handleProgress(OtaUpMessage msg) {
		log.debug("[OTA] 升级进度上报 deviceId={} payload={}（任务状态机 S3 落地）", msg.getDeviceId(), msg.getPayload());
	}

	/**
	 * 结果上报：任务终态判定（S3 落地）；当前仅日志。
	 */
	private void handleResult(OtaUpMessage msg) {
		log.debug("[OTA] 升级结果上报 deviceId={} payload={}（任务状态机 S3 落地）", msg.getDeviceId(), msg.getPayload());
	}

	/**
	 * 主动拉取：查询 PENDING 任务并补推（S3 落地）；当前仅日志。
	 */
	private void handlePull(OtaUpMessage msg) {
		log.debug("[OTA] 升级信息拉取 deviceId={} payload={}（任务补推 S3 落地）", msg.getDeviceId(), msg.getPayload());
	}

}
