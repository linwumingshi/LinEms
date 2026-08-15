package com.energyx.rule.engine.action;

import com.energyx.rule.config.RuleProperties;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作执行器单元测试（模板渲染 / 缺参防御 / 分发失败兜底）。
 */
class ActionExecutorTest {

	private RuleContext ctx() {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("PROPERTY");
		ctx.setDeviceId(10L);
		ctx.setProductKey("energyx_pcs");
		ctx.setProperties(Map.of("cellTemp", 52));
		ctx.setAlarm(Map.of("code", "SCENE_TEMP_HIGH", "state", "ACTIVE"));
		ctx.setTs(1700000000000L);
		return ctx;
	}

	@Test
	@DisplayName("NotifyAction：模板渲染 ${property.xxx} / ${deviceId} / ${ts}")
	void notifyRender() {
		NotifyAction notify = new NotifyAction(new RuleProperties(), null);
		RuleAction action = new RuleAction();
		action.setType("NOTIFY");
		action.setUrl("http://example.com/webhook");
		action.setTemplate("温度 ${property.cellTemp}℃，设备 ${deviceId}，时间 ${ts}");
		String rendered = notify.render(action.getTemplate(), ctx());
		assertTrue(rendered.contains("温度 52℃"));
		assertTrue(rendered.contains("设备 10"));
		assertTrue(rendered.contains("时间 1700000000000"));
		assertFalse(rendered.contains("${"));
	}

	@Test
	@DisplayName("NotifyAction：无模板返回空串不抛异常")
	void notifyNoTemplate() {
		NotifyAction notify = new NotifyAction(new RuleProperties(), null);
		assertTrue(notify.render(null, ctx()).isEmpty());
		assertTrue(notify.render("", ctx()).isEmpty());
	}

	@Test
	@DisplayName("NotifyAction：缺 url 返回失败结果")
	void notifyMissingUrl() {
		NotifyAction notify = new NotifyAction(new RuleProperties(), null);
		RuleAction action = new RuleAction();
		action.setType("NOTIFY");
		ActionResult result = notify.execute(action, ctx());
		assertFalse(result.success());
		assertTrue(result.message().contains("url"));
	}

	@Test
	@DisplayName("ActionExecutorService：未注册类型返回失败")
	void unregisteredType() {
		ActionExecutorService service = new ActionExecutorService(java.util.List.of());
		RuleAction action = new RuleAction();
		action.setType("UNKNOWN");
		ActionResult result = service.execute(action, ctx());
		assertFalse(result.success());
		assertTrue(result.message().contains("未注册"));
	}

	@Test
	@DisplayName("ActionExecutorService：空动作返回失败")
	void nullAction() {
		ActionExecutorService service = new ActionExecutorService(java.util.List.of());
		ActionResult result = service.execute(null, ctx());
		assertFalse(result.success());
	}

	@Test
	@DisplayName("DeviceCommandAction：缺 device 返回失败（不抛异常）")
	void deviceCommandMissingDevice() {
		// CommandClient 需 Feign 代理，仅验证缺参防御路径
		DeviceCommandAction action = new DeviceCommandAction(null);
		RuleAction ruleAction = new RuleAction();
		ruleAction.setType("DEVICE_COMMAND");
		ActionResult result = action.execute(ruleAction, ctx());
		assertFalse(result.success());
	}

	@Test
	@DisplayName("DeviceCommandAction：device 有 productKey 但缺 deviceName 返回失败")
	void deviceCommandMissingName() {
		DeviceCommandAction action = new DeviceCommandAction(null);
		RuleAction ruleAction = new RuleAction();
		ruleAction.setType("DEVICE_COMMAND");
		RuleDevice device = new RuleDevice();
		device.setProductKey("energyx_pcs");
		ruleAction.setDevice(device);
		ruleAction.setCommand("setPower");
		ActionResult result = action.execute(ruleAction, ctx());
		assertFalse(result.success());
		assertTrue(result.message().contains("device"));
	}

	@Test
	@DisplayName("AlarmAction：缺 ruleCode 返回失败")
	void alarmMissingRuleCode() {
		AlarmAction action = new AlarmAction(null);
		RuleAction ruleAction = new RuleAction();
		ruleAction.setType("ALARM");
		ActionResult result = action.execute(ruleAction, ctx());
		assertFalse(result.success());
		assertTrue(result.message().contains("ruleCode"));
	}

	@Test
	@DisplayName("AlarmAction：缺设备上下文返回失败")
	void alarmMissingDevice() {
		AlarmAction action = new AlarmAction(null);
		RuleAction ruleAction = new RuleAction();
		ruleAction.setType("ALARM");
		ruleAction.setRuleCode("SCENE_TEMP_HIGH");
		RuleContext empty = new RuleContext();
		ActionResult result = action.execute(ruleAction, empty);
		assertFalse(result.success());
	}

}
