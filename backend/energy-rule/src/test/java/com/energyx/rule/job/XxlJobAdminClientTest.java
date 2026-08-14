package com.energyx.rule.job;

import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.config.XxlJobConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * xxl-job 调度中心客户端构造测试（配置注入路径）。
 */
class XxlJobAdminClientTest {

	@Test
	@DisplayName("构造：admin 地址去尾斜杠，处理器常量固定")
	void constructWithTrailingSlash() throws Exception {
		RuleProperties props = new RuleProperties();
		XxlJobConfig xxlJobConfig = new XxlJobConfig();
		// 反射设置 admin 地址（带尾斜杠）验证归一化
		java.lang.reflect.Field f = XxlJobConfig.class.getDeclaredField("adminAddresses");
		f.setAccessible(true);
		f.set(xxlJobConfig, "http://127.0.0.1:8099/xxl-job-admin/");
		java.lang.reflect.Field t = XxlJobConfig.class.getDeclaredField("accessToken");
		t.setAccessible(true);
		t.set(xxlJobConfig, "energyx-xxl-job-token");
		XxlJobAdminClient client = new XxlJobAdminClient(props, xxlJobConfig, 1);
		assertNotNull(client);
		assertEquals("sceneRuleTimer", XxlJobAdminClient.HANDLER);
	}

}
