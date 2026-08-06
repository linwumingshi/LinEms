package com.sanduo.energy.shadow;

import com.sanduo.energy.shadow.config.ShadowProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * energy-shadow 启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.shadow.mapper")
@EnableConfigurationProperties(ShadowProperties.class)
public class ShadowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShadowApplication.class, args);
    }
}
