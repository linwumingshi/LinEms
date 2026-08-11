package com.energyx.device.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** xxl-job 执行器装配（低频 cron 任务迁移调度中心） */
@Configuration
public class XxlJobConfig {

	/**
	 * 注册 xxl-job 执行器 Bean。 属性由 application.yml 中 xxl.job.* 前缀绑定（appname=energy-device，端口
	 * 9992）。
	 */
	@Bean
	@ConfigurationProperties(prefix = "xxl.job.executor")
	public XxlJobSpringExecutor xxlJobExecutor() {
		return new XxlJobSpringExecutor();
	}

}
