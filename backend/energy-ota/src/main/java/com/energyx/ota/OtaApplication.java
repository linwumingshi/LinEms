package com.energyx.ota;

import com.energyx.ota.config.OtaProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OTA 固件升级中心入口（energy-ota，端口 8118）。
 *
 * <p>
 * 职责边界：固件升级包管理（全量/差分）、批次升级任务调度（灰度/重试/超时）、 设备 OTA 报文处理（版本上报/进度/结果/拉取）与升级通知下发； 不承载设备管理主数据（归
 * device），不承载指令下发（归 command），仅消费设备版本与下发升级信封。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.ota.mapper")
@EnableFeignClients(basePackages = "com.energyx.ota.client")
@EnableConfigurationProperties(OtaProperties.class)
@EnableScheduling
public class OtaApplication {

	public static void main(String[] args) {
		SpringApplication.run(OtaApplication.class, args);
	}

}
