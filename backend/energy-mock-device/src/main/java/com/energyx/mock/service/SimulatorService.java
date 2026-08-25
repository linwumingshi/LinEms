package com.energyx.mock.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.message.OtaDownMessage;
import com.energyx.common.model.Result;
import com.energyx.mock.client.DeviceFeignClient;
import com.energyx.mock.client.dto.DeviceBrief;
import com.energyx.mock.client.dto.CredentialView;
import com.energyx.mock.client.dto.DeviceCreateReq;
import com.energyx.mock.config.MockDeviceProperties;
import com.energyx.mock.mqtt.MockMqttAuth;
import com.energyx.mock.mqtt.SimulatedDevice;
import com.energyx.mock.ws.WsBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模拟设备编排服务：管理运行时设备注册表，处理自动建档、下行解析、命令自动应答与 OTA 仿真， 并实时推送 WebSocket 事件给前端。
 */
@Slf4j
@Service
public class SimulatorService {

	private final MockDeviceProperties props;

	private final MockMqttAuth auth;

	private final DeviceFeignClient deviceFeignClient;

	private final WsBroadcaster ws;

	private final ObjectMapper objectMapper = new ObjectMapper();

	/** 设备注册表：simId → 设备连接（内存态，进程重启清空） */
	private final ConcurrentHashMap<String, SimulatedDevice> devices = new ConcurrentHashMap<>();

	/** OTA 进度/命令自动应答错峰调度 */
	private ScheduledExecutorService scheduler;

	@PostConstruct
	public void init() {
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "mock-ota-sim");
			t.setDaemon(true);
			return t;
		});
		// 每 15s 扫描未连接设备，断线/失败后自动重试连接（broker 恢复后自动上线）
		scheduler.scheduleWithFixedDelay(this::reconnectSweep, 15, 15, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void destroy() {
		devices.values().forEach(SimulatedDevice::disconnect);
		devices.clear();
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
	}

	public SimulatorService(MockDeviceProperties props, MockMqttAuth auth, DeviceFeignClient deviceFeignClient,
			WsBroadcaster ws) {
		this.props = props;
		this.auth = auth;
		this.deviceFeignClient = deviceFeignClient;
		this.ws = ws;
	}

	/** 自动建档：解析目标设备（upsert）→激活→取回明文密钥→连 broker */
	public SimDeviceView createAuto(String productKey, String deviceName, String deviceType, Long stationId,
			Long enterpriseId, String firmwareVersion) {
		if (devices.size() >= props.getMaxDevices()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "超出最大模拟设备数限制");
		}
		String safeName = sanitize(deviceName);
		// upsert：同名真实设备已存在则直接复用，不再以“设备已存在”阻断仿真
		Long deviceId = resolveDeviceId(productKey, safeName, deviceType, stationId, enterpriseId, firmwareVersion);
		// 尝试激活（部分产品可能已自动激活，失败不阻塞）
		try {
			deviceFeignClient.activate(deviceId);
		}
		catch (Exception e) {
			log.warn("[MOCK] 激活设备 {} 异常(忽略): {}", deviceId, e.getMessage());
		}
		Result<CredentialView> secretRes = deviceFeignClient.regenerateSecret(deviceId);
		if (!secretRes.isSuccess() || secretRes.getData() == null) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "取设备密钥失败: " + secretRes.getMessage());
		}
		String secret = secretRes.getData().getDeviceSecret();
		SimulatedDevice device = new SimulatedDevice(props, auth, productKey, safeName, secret, deviceId, true,
				this::onDownlink);
		connectAndRegister(device);
		// 立即返回视图（状态 CONNECTING），连接走后台回调，避免 broker 无响应拖死控制面
		return device.toView();
	}

	/**
	 * 解析目标设备主键（upsert 语义）：先按 productKey+deviceName 查真实设备， 命中则复用其
	 * deviceId（模拟器仿真已有设备身份），未命中再新建。 目的：模拟器只关心“用某设备身份连 broker”，不应因真实设备已存在而报错。
	 */
	private Long resolveDeviceId(String productKey, String safeName, String deviceType, Long stationId,
			Long enterpriseId, String firmwareVersion) {
		Result<DeviceBrief> exist = deviceFeignClient.byName(productKey, safeName);
		if (exist.isSuccess() && exist.getData() != null && exist.getData().getDeviceId() != null) {
			return exist.getData().getDeviceId();
		}
		DeviceCreateReq req = new DeviceCreateReq();
		req.setProductKey(productKey);
		req.setDeviceName(safeName);
		req.setDeviceType(deviceType == null ? "EDGE_GW" : deviceType);
		req.setStationId(stationId);
		req.setEnterpriseId(enterpriseId);
		req.setFirmwareVersion(firmwareVersion);
		req.setProtocol("MQTT");
		Result<Long> createRes = deviceFeignClient.create(req);
		if (!createRes.isSuccess() || createRes.getData() == null) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建设备失败: " + createRes.getMessage());
		}
		return createRes.getData();
	}

	/** 接管已有设备：密钥由用户给出，直接连 broker */
	public SimDeviceView createTakeover(String productKey, String deviceName, String secret) {
		if (devices.size() >= props.getMaxDevices()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "超出最大模拟设备数限制");
		}
		SimulatedDevice device = new SimulatedDevice(props, auth, productKey, sanitize(deviceName), secret, null, false,
				this::onDownlink);
		connectAndRegister(device);
		return device.toView();
	}

	/**
	 * 登记并异步发起连接：先放入注册表使前端立即可见，再后台发起 MQTT connect。 broker 无响应时设备停在 CONNECTING，由重连扫描兜底。
	 */
	private void connectAndRegister(SimulatedDevice device) {
		devices.put(device.getSimId(), device);
		device.connect(connectCallback(device));
	}

	/** 连接结果回调：成功/失败均实时推送前端，便于观察上线与失败原因 */
	private SimulatedDevice.ConnectCallback connectCallback(SimulatedDevice device) {
		return new SimulatedDevice.ConnectCallback() {
			@Override
			public void onConnected() {
				pushEvent("connected", device, "sys", "{}");
			}

			@Override
			public void onFailure(Throwable cause) {
				pushEvent("connect-failed", device, "sys",
						"{\"error\":\"" + (cause == null ? "unknown" : cause.getMessage()) + "\"}");
			}
		};
	}

	/** 每 15s 扫描一次，对未连接且未在连接中的设备重新发起连接 */
	private void reconnectSweep() {
		try {
			for (SimulatedDevice device : devices.values()) {
				if (!device.isConnected() && !device.isConnecting()) {
					device.connect(connectCallback(device));
				}
			}
		}
		catch (Exception e) {
			log.warn("[MOCK] 重连扫描异常: {}", e.getMessage());
		}
	}

	/** 启动/重连设备（异步，立即返回视图） */
	public SimDeviceView start(String simId) {
		SimulatedDevice device = require(simId);
		if (!device.isConnected()) {
			device.connect(connectCallback(device));
		}
		return device.toView();
	}

	/** 停止设备（断开 MQTT，保留注册表条目） */
	public SimDeviceView stop(String simId) {
		SimulatedDevice device = require(simId);
		device.disconnect();
		return device.toView();
	}

	/** 删除设备（断开并移出注册表） */
	public void remove(String simId) {
		SimulatedDevice device = devices.remove(simId);
		if (device != null) {
			device.disconnect();
		}
	}

	/** 上报属性/事件 */
	public SimDeviceView report(String simId, String type, String json) {
		SimulatedDevice device = require(simId);
		if (!"property".equals(type) && !"event".equals(type)) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "type 仅支持 property/event");
		}
		device.publishUp(type, normalizeReport(type, json));
		return device.toView();
	}

	/** 上下线 */
	public SimDeviceView lifecycle(String simId, boolean online) {
		SimulatedDevice device = require(simId);
		device.reportLifecycle(online);
		return device.toView();
	}

	/** 手动应答命令 */
	public SimDeviceView ack(String simId, String commandId, String status, String resultJson) {
		SimulatedDevice device = require(simId);
		// ack status 采用平台标准枚举名（大写），与 command 服务 CommandState.fromAckStatus 约定一致
		device.ack(commandId, status == null ? "SUCCESS" : status, resultJson);
		pushEvent("ack", device, "up/ack",
				"{\"commandId\":\"" + commandId + "\",\"status\":\"" + (status == null ? "SUCCESS" : status) + "\"}");
		return device.toView();
	}

	/** 列出全部模拟设备快照 */
	public java.util.List<SimDeviceView> list() {
		return devices.values().stream().map(SimulatedDevice::toView).toList();
	}

	/** 下行回调：解析命令/OTA 并仿真应答，实时推送前端 */
	private void onDownlink(SimulatedDevice.DownlinkEvent event) {
		SimulatedDevice device = devices.get(event.getSimId());
		if (device == null) {
			return;
		}
		String topic = event.getTopic();
		String payload = event.getPayload();
		if (topic.endsWith("/down/command")) {
			handleCommand(device, payload);
		}
		else if (topic.endsWith("/ota/down")) {
			handleOta(device, payload);
		}
		else {
			pushEvent("down", device, topic, payload);
		}
	}

	private void handleCommand(SimulatedDevice device, String payload) {
		String commandId = null;
		try {
			JsonNode root = objectMapper.readTree(payload);
			commandId = root.path("commandId").asText(null);
		}
		catch (Exception e) {
			log.warn("[MOCK] 命令报文解析失败: {}", e.getMessage());
		}
		if (commandId != null) {
			device.markPendingCommand(commandId, payload);
		}
		pushEvent("command", device, "down/command", payload);
		// 默认 800ms 后自动应答成功，形成指令中心闭环（手动 ack 可在其之前抢答）
		if (commandId != null) {
			final String cid = commandId;
			scheduler.schedule(() -> {
				if (devices.get(device.getSimId()) != null) {
					try {
						// ack status 采用平台标准枚举名（大写），与 command 服务
						// CommandState.fromAckStatus 约定一致
						device.ack(cid, "SUCCESS", "{\"mock\":\"auto-ack\"}");
						pushEvent("ack", device, "up/ack", "{\"commandId\":\"" + cid + "\",\"status\":\"SUCCESS\"}");
					}
					catch (Exception e) {
						log.warn("[MOCK] 命令自动应答失败: {}", e.getMessage());
					}
				}
			}, 800, TimeUnit.MILLISECONDS);
		}
	}

	private void handleOta(SimulatedDevice device, String payload) {
		pushEvent("ota-down", device, "ota/down", payload);
		OtaDownMessage ota;
		try {
			ota = objectMapper.readValue(payload, OtaDownMessage.class);
		}
		catch (Exception e) {
			log.warn("[MOCK] OTA 下行报文解析失败: {}", e.getMessage());
			return;
		}
		String targetVersion = ota.getVersion();
		Long taskId = ota.getTaskId();
		Long devId = ota.getDeviceId();
		// 错峰上报进度 10→50→100，再 result 成功，最后 inform 目标版本（触发平台成功判定+回写固件版本）
		scheduler.schedule(() -> otaProgress(device, taskId, devId, 10, "downloading"), 400, TimeUnit.MILLISECONDS);
		scheduler.schedule(() -> otaProgress(device, taskId, devId, 50, "flashing"), 1100, TimeUnit.MILLISECONDS);
		scheduler.schedule(() -> otaProgress(device, taskId, devId, 100, "flashing"), 1800, TimeUnit.MILLISECONDS);
		scheduler.schedule(() -> otaResult(device, taskId, devId, targetVersion, true, 0, "ok"), 2300,
				TimeUnit.MILLISECONDS);
		scheduler.schedule(() -> otaInform(device, targetVersion, ota.getModule()), 2500, TimeUnit.MILLISECONDS);
	}

	private void otaProgress(SimulatedDevice device, Long taskId, Long devId, int progress, String state) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("taskId", taskId);
		body.put("deviceId", devId);
		body.put("progress", progress);
		body.put("state", state);
		body.put("ts", System.currentTimeMillis());
		String json = toJson(body);
		device.publishOta("progress", json);
		pushEvent("ota-progress", device, "ota/progress", json);
	}

	private void otaResult(SimulatedDevice device, Long taskId, Long devId, String version, boolean success, int code,
			String msg) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("taskId", taskId);
		body.put("deviceId", devId);
		body.put("success", success);
		body.put("version", version);
		body.put("code", code);
		body.put("msg", msg);
		body.put("ts", System.currentTimeMillis());
		String json = toJson(body);
		device.publishOta("result", json);
		pushEvent("ota-result", device, "ota/result", json);
	}

	private void otaInform(SimulatedDevice device, String version, String module) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("version", version);
		body.put("module", module);
		body.put("ts", System.currentTimeMillis());
		String json = toJson(body);
		device.publishOta("inform", json);
		pushEvent("ota-inform", device, "ota/inform", json);
	}

	/** 推送一条 WebSocket 事件（type + 设备元信息 + topic + payload） */
	private void pushEvent(String type, SimulatedDevice device, String topic, String payload) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", type);
		event.put("simId", device.getSimId());
		event.put("deviceName", device.getDeviceName());
		event.put("ts", System.currentTimeMillis());
		event.put("topic", topic);
		event.put("payload", payload);
		ws.broadcast(toJson(event));
	}

	private SimulatedDevice require(String simId) {
		SimulatedDevice device = devices.get(simId);
		if (device == null) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "模拟设备不存在: " + simId);
		}
		return device;
	}

	/** 设备名净化：broker clientId 按最后一个 _ 拆分，deviceName 禁 _ 与 &，统一替换为 - */
	private String sanitize(String name) {
		if (name == null) {
			return "mock";
		}
		return name.replace('_', '-').replace('&', '-');
	}

	/** 属性/事件上报归一化：包裹为平台约定的上行结构 */
	private String normalizeReport(String type, String json) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("dataType", "report");
		body.put("ts", System.currentTimeMillis());
		try {
			Object props = objectMapper.readValue(json, Object.class);
			body.put("properties", props);
		}
		catch (Exception e) {
			// 非法 JSON 直接透传，交给平台校验
			body.put("properties", json);
		}
		return toJson(body);
	}

	private String toJson(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		}
		catch (Exception e) {
			return "{}";
		}
	}

}
