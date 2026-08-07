package com.energyx.alarm;

import com.energyx.alarm.config.AlarmProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 告警中心启动入口。
 *
 * <p>扫描 {@code com.energyx}（含 energy-common 通用组件）；Mapper 限定 alarm 域；
 * @EnableScheduling 驱动规则缓存定时刷新。消费引擎/WebSocket/ES 写入均为独立组件自装配。</p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.alarm.mapper")
@EnableConfigurationProperties(AlarmProperties.class)
@EnableScheduling
public class AlarmApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlarmApplication.class, args);
    }
}
