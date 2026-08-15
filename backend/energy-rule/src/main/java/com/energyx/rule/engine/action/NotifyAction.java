package com.energyx.rule.engine.action;

import com.energyx.rule.client.NotifyFeignClient;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部通知动作执行器（NOTIFY）。
 *
 * <p>
 * 两种模式：配置模式（action.notifyConfigCode 非空）调消息通知模块 energy-notify POST /send， 携带
 * notifyTemplateCode / notifyContent 与占位符上下文；旧版直发（url 非空）用 JDK HttpClient POST JSON 到
 * webhook，模板变量本地渲染。失败均记执行日志。
 * </p>
 */
@Slf4j
@Component
public class NotifyAction implements ActionExecutor {

	private final RuleProperties props;

	private final HttpClient client;

	private final NotifyFeignClient notifyFeignClient;

	public NotifyAction(RuleProperties props, NotifyFeignClient notifyFeignClient) {
		this.props = props;
		this.notifyFeignClient = notifyFeignClient;
		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	}

	@Override
	public String type() {
		return "NOTIFY";
	}

	@Override
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		if (action.getNotifyConfigCode() != null && !action.getNotifyConfigCode().isBlank()) {
			return sendViaNotifyService(action, ctx);
		}
		if (action.getUrl() == null || action.getUrl().isBlank()) {
			return ActionResult.fail(type(), "NOTIFY 动作需配置通知配置或 url");
		}
		return sendViaWebhook(action, ctx);
	}

	/** 模式一：调 energy-notify 发送（配置 + 模板 + 上下文） */
	private ActionResult sendViaNotifyService(RuleAction action, RuleContext ctx) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("configCode", action.getNotifyConfigCode());
			if (action.getNotifyTemplateCode() != null) {
				body.put("templateCode", action.getNotifyTemplateCode());
			}
			if (action.getNotifyContent() != null) {
				body.put("content", action.getNotifyContent());
			}
			body.put("context", buildContext(ctx));
			var result = notifyFeignClient.send(body);
			if (result == null || !result.isSuccess()) {
				return ActionResult.fail(type(), "通知服务拒绝: " + (result == null ? "空响应" : result.getMessage()));
			}
			Map<String, Object> data = result.getData();
			boolean ok = data != null && Boolean.TRUE.equals(data.get("success"));
			return ok ? ActionResult.ok(type(), "通知已发送: " + data.get("message"))
					: ActionResult.fail(type(), "通知发送失败: " + (data == null ? "" : data.get("message")));
		}
		catch (Exception e) {
			log.error("[Notify] 通知服务调用异常", e);
			return ActionResult.fail(type(), "通知服务调用失败: " + e.getMessage());
		}
	}

	/** 占位符上下文：基础变量 + property 与 alarm 字段平铺 */
	private Map<String, Object> buildContext(RuleContext ctx) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("deviceId", ctx.getDeviceId());
		context.put("deviceName", ctx.getDeviceName());
		context.put("productKey", ctx.getProductKey());
		context.put("triggerType", ctx.getTriggerType());
		context.put("ts", ctx.getTs() == null ? System.currentTimeMillis() : ctx.getTs());
		if (ctx.getProperties() != null) {
			context.putAll(ctx.getProperties());
		}
		context.putAll(ctx.getAlarm());
		return context;
	}

	/** 模式二：旧版 webhook 直发 */
	private ActionResult sendViaWebhook(RuleAction action, RuleContext ctx) {
		try {
			String template = action.getTemplate() == null ? "" : action.getTemplate();
			Map<String, String> headers = action.getHeaders() == null ? Map.of() : action.getHeaders();
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
		if (ctx.getProperties() != null) {
			for (Map.Entry<String, Object> e : ctx.getProperties().entrySet()) {
				result = result.replace("${property." + e.getKey() + "}", String.valueOf(e.getValue()));
			}
		}
		for (Map.Entry<String, Object> e : ctx.getAlarm().entrySet()) {
			result = result.replace("${alarm." + e.getKey() + "}", String.valueOf(e.getValue()));
		}
		result = result.replace("${deviceId}", String.valueOf(ctx.getDeviceId()));
		result = result.replace("${ts}",
				String.valueOf(ctx.getTs() == null ? System.currentTimeMillis() : ctx.getTs()));
		return result;
	}

}
