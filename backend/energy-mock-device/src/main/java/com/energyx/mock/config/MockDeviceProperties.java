package com.energyx.mock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模拟设备配置（energy.mock.*，见 application.yml）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "energy.mock")
public class MockDeviceProperties {

	/** broker 主机 */
	private String brokerHost = "127.0.0.1";

	/** broker 端口（对应 BROKER_MQTT_PORT，默认 18831） */
	private int brokerPort = 18831;

	/** 连接超时（秒） */
	private int connectTimeout = 10;

	/** 心跳间隔（秒） */
	private int keepAlive = 60;

	/** 单实例最大模拟设备数（限流保护） */
	private int maxDevices = 50;

}
