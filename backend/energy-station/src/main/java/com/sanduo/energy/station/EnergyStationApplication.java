package com.sanduo.energy.station;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电站域服务：电站资产与电站-设备关联。
 */
@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
public class EnergyStationApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergyStationApplication.class, args);
    }
}
