package com.energyx.ota.mqtt;

import com.energyx.common.kafka.KafkaRecordHandler;
import com.energyx.common.message.OtaUpMessage;
import com.energyx.ota.service.OtaTaskService;
import com.energyx.ota.service.OtaVersionCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 设备 OTA 上行报文处理器（消费 ota.uplink，key=deviceId 保证单设备保序）。
 *
 * <p>
 * inform → 版本缓存 + 成功判定（版本==目标）；progress/result → 任务状态机推进； pull → 任务补推。版本缓存与任务逻辑委托
 * OtaTaskService/OtaVersionCache。
 * </p>
 */
@Slf4j
@Component
public class OtaUplinkHandler implements KafkaRecordHandler {

	private final OtaVersionCache versionCache;

	private final OtaTaskService taskService;

	private final ObjectMapper objectMapper;

	public OtaUplinkHandler(OtaVersionCache versionCache, OtaTaskService taskService, ObjectMapper objectMapper) {
		this.versionCache = versionCache;
		this.taskService = taskService;
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

	/** 版本上报：写缓存 + 触发成功判定（inform 版本 == 任务目标版本） */
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
			// 成功判定：设备上报版本 == 任务目标版本 → 升级成功（阿里云同款唯一判据）
			taskService.onInform(msg.getDeviceId(), version);
		}
		catch (Exception e) {
			log.warn("[OTA] inform 解析失败 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload(), e);
		}
	}

	/** 进度上报：任务明细进度/阶段推进 */
	private void handleProgress(OtaUpMessage msg) {
		try {
			JsonNode root = objectMapper.readTree(msg.getPayload());
			long taskId = root.path("taskId").asLong(0);
			int progress = root.path("progress").asInt(0);
			String state = root.path("state").asText(null);
			if (taskId <= 0) {
				log.warn("[OTA] progress 缺 taskId deviceId={} payload={}", msg.getDeviceId(), msg.getPayload());
				return;
			}
			taskService.onProgress(taskId, msg.getDeviceId(), progress, state);
		}
		catch (Exception e) {
			log.warn("[OTA] progress 解析失败 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload(), e);
		}
	}

	/** 结果上报：任务终态判定（success + 版本校验） */
	private void handleResult(OtaUpMessage msg) {
		try {
			JsonNode root = objectMapper.readTree(msg.getPayload());
			long taskId = root.path("taskId").asLong(0);
			boolean success = root.path("success").asBoolean(false);
			String version = root.path("version").asText(null);
			String code = root.path("code").asText(null);
			String errMsg = root.path("msg").asText(null);
			if (taskId <= 0) {
				log.warn("[OTA] result 缺 taskId deviceId={} payload={}", msg.getDeviceId(), msg.getPayload());
				return;
			}
			log.info("[OTA] 升级结果 deviceId={} taskId={} success={} version={} code={}", msg.getDeviceId(), taskId,
					success, version, code);
			taskService.onResult(taskId, msg.getDeviceId(), success, version, code, errMsg);
		}
		catch (Exception e) {
			log.warn("[OTA] result 解析失败 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload(), e);
		}
	}

	/** 主动拉取：存在 PENDING 任务则补推 */
	private void handlePull(OtaUpMessage msg) {
		try {
			JsonNode root = objectMapper.readTree(msg.getPayload());
			String version = root.path("version").asText(null);
			if (version != null && !version.isBlank()) {
				versionCache.setVersion(msg.getDeviceId(), version, "main");
			}
			taskService.onPull(msg.getDeviceId());
		}
		catch (Exception e) {
			log.warn("[OTA] pull 处理失败 deviceId={} payload={}", msg.getDeviceId(), msg.getPayload(), e);
		}
	}

}
