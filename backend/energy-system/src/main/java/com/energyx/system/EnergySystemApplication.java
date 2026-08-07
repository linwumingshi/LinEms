package com.energyx.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 系统域服务：租户 / 企业组织树 / RBAC / 操作审计。
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.system.mapper")
public class EnergySystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergySystemApplication.class, args);
    }
}
