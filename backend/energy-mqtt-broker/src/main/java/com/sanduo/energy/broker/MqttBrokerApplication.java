package com.sanduo.energy.broker;

import com.sanduo.energy.broker.config.BrokerProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 自研 Netty MQTT Broker 启动类。
 *
 * <p>职责边界：本模块只做「连接接入 + 消息路由 + 生命周期」，不触碰业务；
 * 上行报文（thing/property/event）由 Phase 5 access adapter 从 Kafka 摄取，Broker 不落库。</p>
 *
 * <p>MQTT 端口（默认 1883）与 Spring 管理端口（默认 8082）分离，
 * 管理端口承载 /actuator、/internal/broker/stats 等运维接口。</p>
 *
 * @author sanduo
 */
@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.broker.mapper")
@EnableConfigurationProperties(BrokerProperties.class)
public class MqttBrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqttBrokerApplication.class, args);
    }
}
