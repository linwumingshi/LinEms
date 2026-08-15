package com.energyx.notify;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 消息通知服务入口（energy-notify，端口 8117）。
 *
 * <p>
 * 提供通知配置 / 通知模板的 CRUD 与多渠道发送（Webhook / 企业微信 / 钉钉 / 邮件）； 场景联动 NOTIFY 动作经 Feign 调用发送接口。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.energyx")
@MapperScan("com.energyx.notify.mapper")
public class NotifyApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotifyApplication.class, args);
	}

}
