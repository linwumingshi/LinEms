package com.sanduo.energy.access;

import com.sanduo.energy.access.config.AccessProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MQTT 接入适配服务（Phase 5）启动类。
 *
 * <p>职责边界：设备面报文的「最后一道转换」——消费 Broker 路由上行的原始报文，
 * 完成物模型校验与类型标准化后投递到标准化 Topic（property/event/ack/raw）；
 * 同时承担平台下行桥接与设备生命周期落库。不承载业务状态（影子/指令状态机归 Phase 6）。</p>
 *
 * @author sanduo
 */
@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.access.mapper")
@EnableConfigurationProperties(AccessProperties.class)
public class AccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }
}
