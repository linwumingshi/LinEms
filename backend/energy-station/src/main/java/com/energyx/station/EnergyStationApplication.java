package com.energyx.station;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电站域服务：电站资产与电站-设备关联。
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.station.mapper")
public class EnergyStationApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyStationApplication.class, args);
	}

}
