package com.energyx.rule.job;

import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.config.XxlJobConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * xxl-job 调度中心 REST 客户端（Phase 11 设计 §10：TIMER 触发器动态 job 管理）。
 *
 * <p>
 * 封装 /jobinfo 系列接口（add/update/remove/pageList），走 JDK HttpClient（与 alarm ES 写入同风格）。 认证：请求头
 * {@code XXL_JOB_ACCESS_TOKEN}（与 XxlJobConfig.accessToken 一致）。
 * </p>
 *
 * <p>
 * job 定位：jobDesc 固定为 {@code rule-{ruleId}}（唯一标识，用于 pageList 模糊查询后匹配）。
 * 新增/更新参数：executorHandler=sceneRuleTimer、executorParam=ruleId、scheduleType=CRON、
 * executorRouteStrategy=FIRST（单实例执行，配合执行器侧分布式锁双保险防重）。
 * </p>
 */
@Slf4j
@Component
public class XxlJobAdminClient {

	private static final String TOKEN_HEADER = "XXL_JOB_ACCESS_TOKEN";

	/** 规则定时 job 的执行器处理器（对应 @XxlJob("sceneRuleTimer")） */
	public static final String HANDLER = "sceneRuleTimer";

	private final String adminAddresses;

	private final String accessToken;

	private final int jobGroup;

	private final HttpClient client;

	/**
	 * @param jobGroup xxl-job 执行器分组 ID（energyx-rule 在调度中心的 jobGroup id，配置缺省 1）
	 */
	public XxlJobAdminClient(RuleProperties props, XxlJobConfig xxlJobConfig,
			@org.springframework.beans.factory.annotation.Value("${energyx.rule.xxl-job-group:1}") int jobGroup) {
		this.adminAddresses = trimTrailingSlash(xxlJobConfig.getAdminAddresses());
		this.accessToken = xxlJobConfig.getAccessToken();
		this.jobGroup = jobGroup;
		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	/** 新增或更新规则定时 job（幂等：按 jobDesc 匹配已存在则 update）。返回成功与否。 */
	public boolean upsert(Long ruleId, String cron) {
		try {
			Integer jobId = findJobId(ruleId);
			String jobDesc = jobDesc(ruleId);
			Map<String, String> form = new HashMap<>();
			form.put("jobGroup", String.valueOf(jobGroup));
			form.put("jobDesc", jobDesc);
			form.put("author", "energyx-rule");
			form.put("scheduleType", "CRON");
			form.put("scheduleConf", cron);
			form.put("glueType", "BEAN");
			form.put("executorHandler", HANDLER);
			form.put("executorParam", String.valueOf(ruleId));
			form.put("executorRouteStrategy", "FIRST");
			form.put("misfireStrategy", "DO_NOTHING");
			form.put("executorBlockStrategy", "SERIAL_EXECUTION");
			form.put("executorTimeout", "0");
			form.put("executorFailRetryCount", "0");
			form.put("triggerStatus", "1");
			if (jobId != null) {
				form.put("id", String.valueOf(jobId));
				post("/jobinfo/update", form);
				log.info("[XxlJob] 更新规则定时 job ruleId={} jobId={} cron={}", ruleId, jobId, cron);
			}
			else {
				post("/jobinfo/add", form);
				log.info("[XxlJob] 新增规则定时 job ruleId={} cron={}", ruleId, cron);
			}
			return true;
		}
		catch (Exception e) {
			log.warn("[XxlJob] 规则定时 job upsert 失败 ruleId={} cron={}", ruleId, cron, e);
			return false;
		}
	}

	/** 删除规则定时 job（幂等：不存在则忽略） */
	public boolean remove(Long ruleId) {
		try {
			Integer jobId = findJobId(ruleId);
			if (jobId == null) {
				return true;
			}
			Map<String, String> form = new HashMap<>();
			form.put("id", String.valueOf(jobId));
			post("/jobinfo/remove", form);
			log.info("[XxlJob] 删除规则定时 job ruleId={} jobId={}", ruleId, jobId);
			return true;
		}
		catch (Exception e) {
			log.warn("[XxlJob] 规则定时 job 删除失败 ruleId={}", ruleId, e);
			return false;
		}
	}

	/** 按 jobDesc 精确匹配 jobId（pageList 返回 JSON，正则提取目标记录的 id；不存在返回 null） */
	private Integer findJobId(Long ruleId) throws IOException, InterruptedException {
		Map<String, String> form = new HashMap<>();
		form.put("jobGroup", String.valueOf(jobGroup));
		form.put("jobDesc", jobDesc(ruleId));
		form.put("executorHandler", HANDLER);
		form.put("triggerStatus", "-1");
		form.put("pageNo", "1");
		form.put("pageSize", "10");
		String body = post("/jobinfo/pageList", form);
		if (body == null) {
			return null;
		}
		// 目标 jobDesc 唯一标记，取它之前最近的 "id":N
		String marker = "\"jobDesc\":\"" + jobDesc(ruleId) + "\"";
		int markerIdx = body.indexOf(marker);
		if (markerIdx < 0) {
			return null;
		}
		String prefix = body.substring(0, markerIdx);
		int lastIdIdx = prefix.lastIndexOf("\"id\":");
		if (lastIdIdx < 0) {
			return null;
		}
		String tail = prefix.substring(lastIdIdx + 5).trim();
		int end = 0;
		while (end < tail.length() && Character.isDigit(tail.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return null;
		}
		return Integer.parseInt(tail.substring(0, end));
	}

	private String post(String path, Map<String, String> form) throws IOException, InterruptedException {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : form.entrySet()) {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(adminAddresses + path))
			.timeout(Duration.ofSeconds(5))
			.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
			.header(TOKEN_HEADER, accessToken)
			.POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			log.warn("[XxlJob] admin API {} 返回 status={}", path, response.statusCode());
			return null;
		}
		return response.body();
	}

	private static String jobDesc(Long ruleId) {
		return "rule-" + ruleId;
	}

	private static String trimTrailingSlash(String url) {
		if (url == null) {
			return "";
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
