package com.sanduo.energy.command;

import com.sanduo.energy.command.config.CommandProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 指令中心启动入口。
 *
 * <p>扫描 {@code com.sanduo.energy}（含 energy-common 的通用组件），
 * Mapper 限定 command 域；@EnableScheduling 驱动 ACK 超时扫描。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.command.mapper")
@EnableConfigurationProperties(CommandProperties.class)
@EnableScheduling
public class CommandApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommandApplication.class, args);
    }
}
