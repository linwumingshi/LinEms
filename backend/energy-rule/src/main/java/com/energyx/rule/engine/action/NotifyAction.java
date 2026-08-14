package com.energyx.rule.engine.action;

import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 外部通知动作执行器（NOTIFY，当前仅 WEBHOOK）。
 *
 * <p>
 * 用 JDK HttpClient POST JSON 到 webhook 地址（与 alarm ES 写入同风格，零额外依赖）； 模板变量 ${property.xxx} /
 * ${alarm.xxx} / ${deviceId} / ${ts} 渲染。超时 5s、失败记执行日志。
 * </p>
 */
@Slf4j
@Component
public class NotifyAction implements ActionExecutor {

	private final RuleProperties props;

	private final HttpClient client;

	public NotifyAction(RuleProperties props) {
		this.props = props;
		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	}

	@Override
	public String type() {
		return "NOTIFY";
	}

	@Override
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		if (action.getUrl() == null || action.getUrl().isBlank()) {
			return ActionResult.fail(type(), "NOTIFY 动作缺 url");
		}
		try {
			String template = action.getTemplate() == null ? "" : action.getTemplate();
			Map<String, String> headers = action.getHeaders() == null ? Map.of() : action.getHeaders();
			// 渲染模板与请求头
			String payload = render(template, ctx);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(action.getUrl()))
				.timeout(Duration.ofMillis(props.getWebhookTimeoutMs()))
				.header("Content-Type", "application/json");
			headers.forEach((k, v) -> builder.header(k, render(v, ctx)));
			HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return ActionResult.ok(type(), "webhook 已通知 status=" + response.statusCode());
			}
			return ActionResult.fail(type(), "webhook 返回异常 status=" + response.statusCode());
		}
		catch (Exception e) {
			return ActionResult.fail(type(), "webhook 调用失败: " + e.getMessage());
		}
	}

	/** 模板变量渲染：${property.xxx} / ${alarm.xxx} / ${deviceId} / ${ts} */
	String render(String template, RuleContext ctx) {
		if (template == null || template.isBlank()) {
			return template == null ? "" : template;
		}
		String result = template;
		// ${property.xxx}
		if (ctx.getProperties() != null) {
			for (Map.Entry<String, Object> e : ctx.getProperties().entrySet()) {
				result = result.replace("${property." + e.getKey() + "}", String.valueOf(e.getValue()));
			}
		}
		// ${alarm.xxx}
		for (Map.Entry<String, Object> e : ctx.getAlarm().entrySet()) {
			result = result.replace("${alarm." + e.getKey() + "}", String.valueOf(e.getValue()));
		}
		// 基础变量
		result = result.replace("${deviceId}", String.valueOf(ctx.getDeviceId()));
		result = result.replace("${ts}",
				String.valueOf(ctx.getTs() == null ? System.currentTimeMillis() : ctx.getTs()));
		return result;
	}

}
