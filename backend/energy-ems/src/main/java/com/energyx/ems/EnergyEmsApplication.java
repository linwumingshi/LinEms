package com.energyx.ems;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.ems.mapper")
@EnableScheduling
public class EnergyEmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyEmsApplication.class, args);
	}

}
