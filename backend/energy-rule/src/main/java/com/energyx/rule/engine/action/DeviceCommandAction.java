package com.energyx.rule.engine.action;

import com.energyx.rule.client.CommandClient;
import com.energyx.rule.engine.RuleContext;
import com.energyx.rule.model.RuleAction;
import com.energyx.rule.model.RuleDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 设备控制命令动作执行器（DEVICE_COMMAND）。
 *
 * <p>
 * 调 energy-command POST /api/command（Feign + Nacos 服务名解析），命令的幂等/超时/重试/离线队列
 * 全部由命令中心负责；规则引擎只构造 CreateCommandRequest（createBy=0 系统自动）。
 * </p>
 */
@Slf4j
@Component
public class DeviceCommandAction implements ActionExecutor {

	private final CommandClient commandClient;

	public DeviceCommandAction(CommandClient commandClient) {
		this.commandClient = commandClient;
	}

	@Override
	public String type() {
		return "DEVICE_COMMAND";
	}

	@Override
	public ActionResult execute(RuleAction action, RuleContext ctx) {
		RuleDevice device = action.getDevice();
		if (device == null || device.getProductKey() == null || device.getDeviceName() == null) {
			return ActionResult.fail(type(), "DEVICE_COMMAND 动作缺 device（productKey/deviceName）");
		}
		try {
			String commandId = commandClient.dispatch(device.getProductKey(), device.getDeviceName(),
					action.getCommand(), action.getParams(), action.getTimeoutMs(), action.getMaxRetry(), 0L);
			return ActionResult.ok(type(), "命令已下发 commandId=" + commandId);
		}
		catch (Exception e) {
			log.warn("[Rule] 命令下发失败 productKey={} deviceName={} command={}", device.getProductKey(),
					device.getDeviceName(), action.getCommand(), e);
			return ActionResult.fail(type(), "命令下发失败: " + e.getMessage());
		}
	}

}
