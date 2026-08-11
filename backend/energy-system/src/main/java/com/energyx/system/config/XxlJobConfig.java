package com.energyx.system.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** xxl-job 执行器装配（低频 cron 任务迁移调度中心） */
@Configuration
public class XxlJobConfig {

	@Value("${xxl.job.admin.addresses}")
	private String adminAddresses;

	@Value("${xxl.job.accessToken}")
	private String accessToken;

	@Value("${xxl.job.executor.appname}")
	private String appname;

	@Value("${xxl.job.executor.address:}")
	private String address;

	@Value("${xxl.job.executor.ip:}")
	private String ip;

	@Value("${xxl.job.executor.port:9994}")
	private int port;

	@Value("${xxl.job.executor.logpath:./logs/xxl-job/}")
	private String logPath;

	@Value("${xxl.job.executor.logretentiondays:30}")
	private int logRetentionDays;

	/** 注册 xxl-job 执行器：admin 地址/令牌/执行器定位均手动注入，确保注册链路完整 */
	@Bean
	public XxlJobSpringExecutor xxlJobExecutor() {
		XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
		executor.setAdminAddresses(adminAddresses);
		executor.setAccessToken(accessToken);
		executor.setAppname(appname);
		executor.setAddress(address);
		executor.setIp(ip);
		executor.setPort(port);
		executor.setLogPath(logPath);
		executor.setLogRetentionDays(logRetentionDays);
		return executor;
	}

}
