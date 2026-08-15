package com.energyx.notify.channel;

import com.energyx.notify.model.NotifyConfigRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Webhook 渠道执行器：HTTP POST 到配置的 url，headers 按配置携带，body 为 content 原文。
 */
@Component
public class WebhookExecutor implements ChannelExecutor {

	private final HttpClient httpClient;

	public WebhookExecutor(@Value("${energyx.notify.webhook-timeout-ms:5000}") long timeoutMs) {
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
	}

	@Override
	public String channel() {
		return NotifyChannel.WEBHOOK.getCode();
	}

	@Override
	public SendResult send(NotifyConfigRow config, String title, String content) {
		WebhookCfg cfg;
		try {
			cfg = WebhookCfg.parse(config.getChannelConfig());
		}
		catch (Exception e) {
			return SendResult.fail("webhook 配置解析失败: " + e.getMessage());
		}
		if (cfg.url == null || cfg.url.isBlank()) {
			return SendResult.fail("webhook 配置缺 url");
		}
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(cfg.url))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(content == null ? "" : content,
						java.nio.charset.StandardCharsets.UTF_8));
			if (cfg.headers != null) {
				cfg.headers.forEach((k, v) -> {
					if (k != null && v != null)
						builder.header(k, String.valueOf(v));
				});
			}
			HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			return resp.statusCode() >= 200 && resp.statusCode() < 300
					? SendResult.ok("webhook 已发送 HTTP " + resp.statusCode())
					: SendResult.fail("webhook 返回 HTTP " + resp.statusCode() + "：" + truncate(resp.body()));
		}
		catch (Exception e) {
			return SendResult.fail("webhook 发送异常: " + e.getMessage());
		}
	}

	private static String truncate(String s) {
		if (s == null)
			return "";
		return s.length() > 200 ? s.substring(0, 200) : s;
	}

	/** channel_config JSON 投影（WEBHOOK 结构） */
	public static class WebhookCfg {

		String url;

		java.util.Map<String, Object> headers;

		static WebhookCfg parse(String json) throws Exception {
			var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			var node = mapper.readTree(json);
			WebhookCfg c = new WebhookCfg();
			if (node.has("url"))
				c.url = node.get("url").asText();
			if (node.has("headers") && node.get("headers").isObject()) {
				c.headers = mapper.convertValue(node.get("headers"),
						new com.fasterxml.jackson.core.type.TypeReference<>() {
						});
			}
			return c;
		}

	}

}
