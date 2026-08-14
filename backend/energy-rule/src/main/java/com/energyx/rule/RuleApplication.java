package com.energyx.rule;

import com.energyx.rule.config.RuleProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 场景联动与规则编排服务启动入口。
 *
 * <p>
 * 扫描 {@code com.energyx}（含 energy-common 通用组件）；Mapper 限定 rule 域；
 * {@code @EnableScheduling} 驱动规则缓存定时刷新（与 alarm 同模式）。 引擎组件（Kafka 消费者 / 触发匹配 / 动作执行 /
 * xxl-job 执行器）均为独立组件自装配。 Feign 客户端限定 rule.client 包（调命令中心/告警中心）。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.rule.mapper")
@EnableConfigurationProperties(RuleProperties.class)
@EnableFeignClients(basePackages = "com.energyx.rule.client")
@EnableScheduling
public class RuleApplication {

	public static void main(String[] args) {
		SpringApplication.run(RuleApplication.class, args);
	}

}
