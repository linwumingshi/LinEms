package com.energyx.rule.engine.action;

import com.energyx.common.model.Result;
import com.energyx.rule.client.AlarmFeignClient;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 触发告警动作执行器（ALARM）。
 *
 * <p>
 * 调 energy-alarm POST /api/alarm/trigger（Feign），以「场景联动」名义创建告警记录并走 既有 iot-alarm / WS / ES
 * 发布链路；告警的合并/静默/通知由告警中心负责。
 * </p>
 */
@Slf4j
@Component
public class AlarmAction implements ActionExecutor {

	private final AlarmFeignClient alarmFeignClient;

	public AlarmAction(AlarmFeignClient alarmFeignClient) {
		this.alarmFeignClient = alarmFeignClient;
	}

	@Override
	public String type() {
		return "ALARM";
	}

	@Override
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		if (action.getRuleCode() == null || action.getRuleCode().isBlank()) {
			return ActionResult.fail(type(), "ALARM 动作缺 ruleCode");
		}
		if (ctx.getDeviceId() == null) {
			return ActionResult.fail(type(), "ALARM 动作需设备上下文（deviceId）");
		}
		try {
			Map<String, Object> body = new HashMap<>();
			body.put("deviceId", ctx.getDeviceId());
			if (ctx.getProductKey() != null) {
				body.put("productKey", ctx.getProductKey());
			}
			body.put("ruleCode", action.getRuleCode());
			body.put("severity", action.getSeverity() == null ? 3 : action.getSeverity());
			if (action.getMessage() != null) {
				body.put("message", action.getMessage());
			}
			Result<Void> result = alarmFeignClient.trigger(body);
			if (result == null || !result.isSuccess()) {
				return ActionResult.fail(type(), "告警服务拒绝: " + (result == null ? "空响应" : result.getMessage()));
			}
			return ActionResult.ok(type(), "告警已触发 ruleCode=" + action.getRuleCode());
		}
		catch (Exception e) {
			log.warn("[Rule] 告警触发失败 ruleCode={} deviceId={}", action.getRuleCode(), ctx.getDeviceId(), e);
			return ActionResult.fail(type(), "告警触发失败: " + e.getMessage());
		}
	}

}
