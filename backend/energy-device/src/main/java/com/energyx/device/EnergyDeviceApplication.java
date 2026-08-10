package com.energyx.device;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 设备域服务：统一设备树 / 凭据 / 在线记录 / 分组标签。
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.device.mapper")
public class EnergyDeviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyDeviceApplication.class, args);
	}

}
