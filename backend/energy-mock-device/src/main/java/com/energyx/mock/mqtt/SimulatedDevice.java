package com.energyx.mock.mqtt;

import com.energyx.mock.config.MockDeviceProperties;
import com.energyx.mock.service.SimDeviceView;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 单台模拟设备：封装一个 Paho MQTT 连接，作为真实设备接入 broker。
 *
 * <p>
 * 连接采用 Paho 非阻塞 connect（带回调），因此即便 broker 无响应（不回 CONNACK）， 调用线程（HTTP 控制面）也不会被拖死：设备进入
 * CONNECTING，待 broker 恢复后 {@code connectComplete} 触发订阅与上线，或由 SimulatorService 的重连扫描兜底重连。
 * </p>
 *
 * <p>
 * 收到下行时回调 {@code onDownlink}（由 SimulatorService 解析并仿真应答/OTA 进度）。 上行发布
 * {@code up/{property|event|lifecycle|ack}} 与 {@code ota/{inform|progress|result|pull}}。
 * </p>
 */
@Slf4j
public class SimulatedDevice {

	/** 设备侧上行 QoS */
	private static final int QOS = 1;

	private final MockDeviceProperties props;

	private final MockMqttAuth auth;

	@Getter
	private final String productKey;

	@Getter
	private final String deviceName;

	/** 设备标识 = clientId = {productKey}_{deviceName} */
	@Getter
	private final String simId;

	private final String secret;

	/** 平台设备主键（自动建档时已知，接管模式可为空） */
	@Getter
	private final Long deviceId;

	/** true=自动建档（平台创建设备并取密钥）；false=接管已有设备（密钥由用户给出） */
	@Getter
	private final boolean autoProvisioned;

	private final Consumer<DownlinkEvent> onDownlink;

	private MqttAsyncClient client;

	@Getter
	private volatile boolean connected = false;

	@Getter
	private volatile boolean online = false;

	/** 已发起连接但 CONNACK 未达（含重连扫描期间），用于避免重复 connect + 前端展示"连接中" */
	@Getter
	private volatile boolean connecting = false;

	/** 最近一次连接失败原因（便于前端排查） */
	@Getter
	private volatile String lastError;

	/** 设备本地日志（bounded 80），命名 logs 以避开 @Slf4j 的 log 日志器 */
	private final List<Map<String, Object>> logs = new CopyOnWriteArrayList<>();

	private final List<Map<String, Object>> pendingCommands = new CopyOnWriteArrayList<>();

	private final Object publishLock = new Object();

	/** connect 与 handleConnected 的互斥锁，保证"上线处理"只执行一次 */
	private final Object connectGuard = new Object();

	public SimulatedDevice(MockDeviceProperties props, MockMqttAuth auth, String productKey, String deviceName,
			String secret, Long deviceId, boolean autoProvisioned, Consumer<DownlinkEvent> onDownlink) {
		this.props = props;
		this.auth = auth;
		this.productKey = productKey;
		this.deviceName = deviceName;
		this.simId = productKey + "_" + deviceName;
		this.secret = secret;
		this.deviceId = deviceId;
		this.autoProvisioned = autoProvisioned;
		this.onDownlink = onDownlink;
	}

	/**
	 * 异步发起 MQTT 连接（不阻塞调用线程）。
	 *
	 * <p>
	 * 采用 Paho 非阻塞 {@code connect(options, userContext, listener)}：CONNACK 到达或失败走回调， 因此
	 * broker 无响应也不会拖死 HTTP 控制面。重连（含断线后）由 SimulatorService 的扫描统一发起， 本类不开启 Paho
	 * 自动重连，避免多套重连机制争用同一 clientId。
	 * </p>
	 * @param callback 连接结果回调（成功/失败各至多触发一次）
	 */
	public void connect(ConnectCallback callback) {
		if (connected) {
			if (callback != null) {
				callback.onConnected();
			}
			return;
		}
		// 防止并发重复发起连接
		synchronized (connectGuard) {
			if (connecting) {
				return;
			}
			connecting = true;
		}
		try {
			// 若存在旧客户端先关闭，复用同一 clientId 重新发起连接
			if (client != null) {
				try {
					client.close();
				}
				catch (MqttException ignored) {
					// 忽略关闭异常
				}
			}
			String serverUri = "tcp://" + props.getBrokerHost() + ":" + props.getBrokerPort();
			client = new MqttAsyncClient(serverUri, simId, new MemoryPersistence());
			MqttConnectOptions options = auth.buildOptions(simId, secret);
			client.setCallback(new MqttCallbackExtended() {
				@Override
				public void connectComplete(boolean reconnect, String serverURI) {
					// CONNACK 到达：统一在此处理订阅与上线（不同 Paho 版本 onSuccess/connectComplete
					// 触发顺序不一，做幂等）
					handleConnected(callback, reconnect);
				}

				@Override
				public void connectionLost(Throwable cause) {
					connected = false;
					online = false;
					// 断线后清除 connecting 标记，交由 SimulatorService 重连扫描兜底重连，
					// 否则 connecting 残留会让扫描跳过本设备，broker 恢复后也无法自动重连
					connecting = false;
					appendLog("sys", "连接断开: " + (cause == null ? "unknown" : cause.getMessage()), null);
				}

				@Override
				public void messageArrived(String topic, MqttMessage message) {
					SimulatedDevice.this.messageArrived(topic, message);
				}

				@Override
				public void deliveryComplete(IMqttDeliveryToken token) {
					// 无需处理
				}
			});
			// 非阻塞发起连接：CONNACK 未达时由上层重连扫描兜底，不阻塞调用线程
			client.connect(options, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
				@Override
				public void onSuccess(IMqttToken asyncActionToken) {
					handleConnected(callback, false);
				}

				@Override
				public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
					connecting = false;
					lastError = exception == null ? "unknown" : exception.getMessage();
					log.warn("[MOCK] 设备 {} 连接 broker 失败: {}", simId, lastError);
					appendLog("sys", "连接失败: " + lastError, null);
					if (callback != null) {
						callback.onFailure(exception);
					}
				}
			});
			appendLog("sys", "已发起连接 broker " + serverUri, null);
		}
		catch (MqttException e) {
			connecting = false;
			lastError = e.getMessage();
			log.warn("[MOCK] 设备 {} 建立客户端/发起连接异常: {}", simId, e.getMessage());
			appendLog("sys", "连接异常: " + lastError, null);
			if (callback != null) {
				callback.onFailure(e);
			}
		}
	}

	/** CONNACK 到达后的统一处理：置连接态、订阅下行/OTA、上报上线（幂等，仅在从未"已连接"时执行） */
	private void handleConnected(ConnectCallback callback, boolean reconnect) {
		synchronized (connectGuard) {
			if (connected) {
				return;
			}
			connected = true;
		}
		connecting = false;
		lastError = null;
		log.info("[MOCK] 设备 {} 已连上 broker {}:{}（reconnect={}）", simId, props.getBrokerHost(), props.getBrokerPort(),
				reconnect);
		try {
			// 订阅本设备下行（命令/属性设置）与 OTA 下发
			client.subscribe(productKey + "/" + deviceName + "/down/#", QOS);
			client.subscribe(productKey + "/" + deviceName + "/ota/down", QOS);
			appendLog("sys", "已连接 broker 并订阅 down/#、ota/down", null);
			// 上线
			reportLifecycle(true);
		}
		catch (MqttException e) {
			log.warn("[MOCK] 设备 {} 订阅/上线失败: {}", simId, e.getMessage());
		}
		if (callback != null) {
			callback.onConnected();
		}
	}

	/** Paho 异步消息回调（由 setCallback 注册的匿名 MqttCallback 转发至此） */
	public void messageArrived(String topic, MqttMessage message) {
		String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
		appendLog("in", "收到下行 topic=" + topic, payload);
		if (onDownlink != null) {
			onDownlink.accept(new DownlinkEvent(simId, deviceName, topic, payload));
		}
	}

	/** 发布属性/事件/生命周期上行 */
	public void publishUp(String type, String json) {
		publish(productKey + "/" + deviceName + "/up/" + type, json);
	}

	/** 发布 OTA 上行（inform/progress/result/pull） */
	public void publishOta(String type, String json) {
		publish(productKey + "/" + deviceName + "/ota/" + type, json);
	}

	/** 上报上下线（lifecycle） */
	public void reportLifecycle(boolean online) {
		this.online = online;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("eventType", online ? "ONLINE" : "OFFLINE");
		body.put("ts", System.currentTimeMillis());
		publishUp("lifecycle", toJson(body));
		appendLog("out", "上报" + (online ? "上线" : "下线"), toJson(body));
	}

	/** 应答平台命令（up/ack） */
	public void ack(String commandId, String status, String resultJson) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("commandId", commandId);
		body.put("status", status);
		body.put("result", resultJson == null ? new LinkedHashMap<>() : parseJson(resultJson));
		body.put("ts", System.currentTimeMillis());
		String json = toJson(body);
		publishUp("ack", json);
		appendLog("out", "应答命令 commandId=" + commandId + " status=" + status, json);
		// 清除待应答
		pendingCommands.removeIf(c -> commandId.equals(String.valueOf(c.get("commandId"))));
	}

	/** 断开连接（不下线，直接断；连接丢失由回调处理） */
	public synchronized void disconnect() {
		try {
			if (client != null && client.isConnected()) {
				// MqttAsyncClient.disconnect 为非阻塞，带超时等待优雅断开再关闭
				IMqttToken disconnectToken = client.disconnect();
				disconnectToken.waitForCompletion(3000);
			}
		}
		catch (MqttException e) {
			log.warn("[MOCK] 断开设备 {} 异常: {}", simId, e.getMessage());
		}
		finally {
			connected = false;
			connecting = false;
			online = false;
			try {
				if (client != null) {
					client.close();
				}
			}
			catch (MqttException ignored) {
				// 忽略关闭异常
			}
		}
	}

	/** 记录一条待应答命令（供前端展示"命令到达"） */
	public void markPendingCommand(String commandId, String raw) {
		Map<String, Object> cmd = new LinkedHashMap<>();
		cmd.put("commandId", commandId);
		cmd.put("raw", raw);
		cmd.put("ts", System.currentTimeMillis());
		pendingCommands.add(cmd);
	}

	private void publish(String topic, String json) {
		synchronized (publishLock) {
			if (client == null || !connected) {
				// 未连接：丢弃发布（模拟器侧容错，避免把控制面异常抛给前端）
				appendLog("out", "未连接，丢弃发布 topic=" + topic, json);
				log.debug("[MOCK] 设备 {} 未连接，丢弃发布 {}", simId, topic);
				return;
			}
			try {
				// 经 MqttTopic.publish 拿到投递 token，再带超时等待 PUBACK：
				// 避免 broker 不回 PUBACK 时永久阻塞（旧 broker 对 ota/* 等未授权 topic 不下发确认）
				MqttMessage msg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
				msg.setQos(QOS);
				// MqttAsyncClient.publish 返回投递 token，带超时等待 PUBACK：
				// 避免 broker 不回 PUBACK 时永久阻塞（旧 broker 对 ota/* 等未授权 topic 不下发确认）
				IMqttDeliveryToken token = client.publish(topic, msg);
				token.waitForCompletion(8000);
				if (token.getException() != null) {
					throw token.getException();
				}
			}
			catch (MqttException e) {
				log.warn("[MOCK] 设备 {} 发布 {} 失败: {}", simId, topic, e.getMessage());
				throw new IllegalStateException("发布失败: " + e.getMessage(), e);
			}
		}
	}

	private void appendLog(String dir, String note, String payload) {
		if (logs.size() > 80) {
			logs.subList(0, logs.size() - 80).clear();
		}
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("ts", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
		entry.put("dir", dir);
		entry.put("note", note);
		if (payload != null) {
			entry.put("payload", payload);
		}
		logs.add(entry);
	}

	/** 设备快照视图（供 REST/WS 返回前端） */
	public SimDeviceView toView() {
		SimDeviceView view = new SimDeviceView();
		view.setSimId(simId);
		view.setProductKey(productKey);
		view.setDeviceName(deviceName);
		view.setDeviceId(deviceId);
		view.setAutoProvisioned(autoProvisioned);
		view.setConnected(connected);
		view.setOnline(online);
		view.setConnecting(connecting);
		view.setLastError(lastError);
		// 连接态聚合：ONLINE(已连接+已上线) / CONNECTED(已连接未上线) / CONNECTING(连接中) / FAILED(失败) /
		// OFFLINE(离线)
		String status;
		if (connected && online) {
			status = "ONLINE";
		}
		else if (connected) {
			status = "CONNECTED";
		}
		else if (connecting) {
			status = "CONNECTING";
		}
		else if (lastError != null) {
			status = "FAILED";
		}
		else {
			status = "OFFLINE";
		}
		view.setStatus(status);
		view.setRecentLogs(new ArrayList<>(logs));
		view.setPendingCommands(new ArrayList<>(pendingCommands));
		return view;
	}

	private static String toJson(Object obj) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
		}
		catch (Exception e) {
			return "{}";
		}
	}

	private static Object parseJson(String s) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Object.class);
		}
		catch (Exception e) {
			return s;
		}
	}

	/** 下行事件（topic + 原始 JSON），交给 SimulatorService 解析 */
	public static class DownlinkEvent {

		private final String simId;

		private final String deviceName;

		private final String topic;

		private final String payload;

		public DownlinkEvent(String simId, String deviceName, String topic, String payload) {
			this.simId = simId;
			this.deviceName = deviceName;
			this.topic = topic;
			this.payload = payload;
		}

		public String getSimId() {
			return simId;
		}

		public String getDeviceName() {
			return deviceName;
		}

		public String getTopic() {
			return topic;
		}

		public String getPayload() {
			return payload;
		}

	}

	/** 异步连接结果回调 */
	public interface ConnectCallback {

		/** 连接成功（CONNACK 已处理完订阅与上线） */
		void onConnected();

		/** 连接失败（broker 无响应或鉴权被拒），上层据此重连 */
		void onFailure(Throwable cause);

	}

}
