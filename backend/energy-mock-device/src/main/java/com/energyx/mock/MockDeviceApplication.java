package com.energyx.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 模拟设备入口（energy-mock-device，端口 8119）。
 *
 * <p>
 * 职责：作为"设备仿真器"接入平台——用 Paho MQTT 客户端按 HMAC 契约连 broker， 模拟真实设备上报属性/事件、接收命令与 OTA 下发；并向前端提供
 * REST 控制面（/api/mock/**）与 WebSocket（/ws/mock） 做可视化验证。 无 DB：设备注册表为运行时内存态。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@EnableFeignClients(basePackages = "com.energyx.mock.client")
@EnableScheduling
public class MockDeviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockDeviceApplication.class, args);
	}

}
