package com.energyx.notify.channel;

import com.energyx.notify.model.NotifyConfigRow;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 钉钉群机器人渠道执行器：POST webhook（支持加签 secret），消息体
 * {@code {"msgtype":"text","text":{"content":"..."}}}。
 */
@Component
public class DingTalkExecutor implements ChannelExecutor {

	private final HttpClient httpClient;

	public DingTalkExecutor() {
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public String channel() {
		return NotifyChannel.DINGTALK.getCode();
	}

	@Override
	public SendResult send(NotifyConfigRow config, String title, String content) {
		String webhook;
		String secret;
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(config.getChannelConfig());
			webhook = node.has("webhook") ? node.get("webhook").asText() : null;
			secret = node.has("secret") ? node.get("secret").asText() : null;
		}
		catch (Exception e) {
			return SendResult.fail("钉钉配置解析失败: " + e.getMessage());
		}
		if (webhook == null || webhook.isBlank()) {
			return SendResult.fail("钉钉配置缺 webhook");
		}
		String url = webhook;
		if (secret != null && !secret.isBlank()) {
			try {
				long ts = System.currentTimeMillis();
				url = webhook + (webhook.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign(secret, ts);
			}
			catch (Exception e) {
				return SendResult.fail("钉钉签名计算失败: " + e.getMessage());
			}
		}
		String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + jsonEscape(content == null ? "" : content)
				+ "}}";
		try {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() == 200 && resp.body().contains("\"errcode\":0")) {
				return SendResult.ok("钉钉已发送");
			}
			return SendResult.fail("钉钉返回: " + resp.body());
		}
		catch (Exception e) {
			return SendResult.fail("钉钉发送异常: " + e.getMessage());
		}
	}

	/** 钉钉加签：HmacSHA256(secret, timestamp + "\n" + secret) → Base64 → URLEncode */
	private static String sign(String secret, long timestamp) throws Exception {
		String stringToSign = timestamp + "\n" + secret;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signStr = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
		return URLEncoder.encode(signStr, StandardCharsets.UTF_8);
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
