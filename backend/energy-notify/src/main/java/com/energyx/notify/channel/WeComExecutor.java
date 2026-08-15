package com.energyx.notify.channel;

import com.energyx.notify.model.NotifyConfigRow;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 企业微信群机器人渠道执行器：POST webhook，消息体 {@code {"msgtype":"text","text":{"content":"..."}}}。
 */
@Component
public class WeComExecutor implements ChannelExecutor {

	private final HttpClient httpClient;

	public WeComExecutor() {
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public String channel() {
		return NotifyChannel.WECOM.getCode();
	}

	@Override
	public SendResult send(NotifyConfigRow config, String title, String content) {
		String webhook;
		try {
			webhook = extractWebhook(config.getChannelConfig());
		}
		catch (Exception e) {
			return SendResult.fail("企业微信配置解析失败: " + e.getMessage());
		}
		if (webhook == null || webhook.isBlank()) {
			return SendResult.fail("企业微信配置缺 webhook");
		}
		String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + jsonEscape(content == null ? "" : content)
				+ "}}";
		try {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(webhook))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() == 200 && resp.body().contains("\"errcode\":0")) {
				return SendResult.ok("企业微信已发送");
			}
			return SendResult.fail("企业微信返回: " + resp.body());
		}
		catch (Exception e) {
			return SendResult.fail("企业微信发送异常: " + e.getMessage());
		}
	}

	/** 从 channel_config JSON 取 webhook 字段 */
	private static String extractWebhook(String json) throws Exception {
		var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		return node.has("webhook") ? node.get("webhook").asText() : null;
	}

	private static String jsonEscape(String s) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s);
		}
		catch (Exception e) {
			return "\"\"";
		}
	}

}
