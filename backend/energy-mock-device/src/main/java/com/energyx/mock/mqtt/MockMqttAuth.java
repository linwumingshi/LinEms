package com.energyx.mock.mqtt;

import com.energyx.mock.config.MockDeviceProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * MQTT 连接鉴权构造器（设备侧 HMAC-SHA256 契约，对齐 energy-mqtt-broker DeviceAuthService）。
 *
 * <pre>
 *   clientId = {productKey}_{deviceName}
 *   username = {clientId}&{timestamp}&{nonce}
 *   password = hex(HMAC-SHA256(deviceSecret, username))
 * </pre>
 */
@Slf4j
@Component
public class MockMqttAuth {

	private final MockDeviceProperties props;

	public MockMqttAuth(MockDeviceProperties props) {
		this.props = props;
	}

	/** 为指定设备构造 Paho 连接选项（含 HMAC 签名） */
	public MqttConnectOptions buildOptions(String clientId, String deviceSecret) {
		long ts = System.currentTimeMillis();
		String nonce = UUID.randomUUID().toString().replace("-", "");
		String username = clientId + "&" + ts + "&" + nonce;
		String password = hmacSha256Hex(deviceSecret, username);
		MqttConnectOptions options = new MqttConnectOptions();
		options.setUserName(username);
		options.setPassword(password.toCharArray());
		// 模拟器重连由 SimulatorService 的重连扫描统一发起（避免与 Paho 内置重连争用同一 clientId）
		options.setAutomaticReconnect(false);
		options.setCleanSession(true);
		options.setConnectionTimeout(props.getConnectTimeout());
		options.setKeepAliveInterval(props.getKeepAlive());
		return options;
	}

	/** HMAC-SHA256 hex（纯函数，对齐 broker HmacSigner） */
	public static String hmacSha256Hex(String key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException("HMAC-SHA256 计算失败", e);
		}
	}

	/** 常数时间比较（与 broker 校验对称，工具方法备用） */
	public static boolean constantTimeEquals(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

}
