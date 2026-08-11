package com.energyx.ems;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.ems.mapper")
@EnableFeignClients(basePackages = "com.energyx.ems.client")
@EnableScheduling
public class EnergyEmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyEmsApplication.class, args);
	}

}
