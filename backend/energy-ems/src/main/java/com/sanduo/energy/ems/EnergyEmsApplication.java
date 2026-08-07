package com.sanduo.energy.ems;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.ems.mapper")
@EnableScheduling
public class EnergyEmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnergyEmsApplication.class, args);
    }
}
