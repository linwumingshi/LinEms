package com.energyx.mock.web;

import com.energyx.common.model.Result;
import com.energyx.mock.service.SimDeviceView;
import com.energyx.mock.service.SimulatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模拟设备 REST 控制面（/api/mock/**）。
 *
 * <p>
 * 前端经 vite 代理直连 energy-mock-device:8119（与 OTA 同款做法，绕过旧网关进程）； 租户由 energy-common 的
 * TenantContextFilter 从 X-Tenant-Id 注入。
 * </p>
 */
@RestController
@RequestMapping("/api/mock")
public class MockDeviceController {

	private final SimulatorService simulatorService;

	public MockDeviceController(SimulatorService simulatorService) {
		this.simulatorService = simulatorService;
	}

	/** 列出全部模拟设备 */
	@GetMapping("/devices")
	public Result<List<SimDeviceView>> list() {
		return Result.ok(simulatorService.list());
	}

	/** 新建模拟设备：mode=takeover 接管已有设备（需 secret），否则自动建档 */
	@PostMapping("/devices")
	public Result<SimDeviceView> create(@RequestBody Map<String, Object> body) {
		String mode = str(body, "mode");
		String productKey = str(body, "productKey");
		String deviceName = str(body, "deviceName");
		if (productKey == null || productKey.isBlank() || deviceName == null || deviceName.isBlank()) {
			return Result.fail(40002, "productKey 与 deviceName 必填");
		}
		if ("takeover".equals(mode)) {
			String secret = str(body, "secret");
			if (secret == null || secret.isBlank()) {
				return Result.fail(40002, "接管模式需提供 secret");
			}
			return Result.ok(simulatorService.createTakeover(productKey, deviceName, secret));
		}
		SimDeviceView view = simulatorService.createAuto(productKey, deviceName, str(body, "deviceType"),
				num(body, "stationId"), num(body, "enterpriseId"), str(body, "firmwareVersion"));
		return Result.ok(view);
	}

	/** 启动/重连设备 */
	@PostMapping("/devices/{simId}/start")
	public Result<SimDeviceView> start(@PathVariable("simId") String simId) {
		return Result.ok(simulatorService.start(simId));
	}

	/** 停止设备（断开 MQTT） */
	@PostMapping("/devices/{simId}/stop")
	public Result<SimDeviceView> stop(@PathVariable("simId") String simId) {
		return Result.ok(simulatorService.stop(simId));
	}

	/** 删除设备 */
	@DeleteMapping("/devices/{simId}")
	public Result<Void> remove(@PathVariable("simId") String simId) {
		simulatorService.remove(simId);
		return Result.ok();
	}

	/** 上报属性/事件（body: type=property|event, json=属性对象 JSON 字符串） */
	@PostMapping("/devices/{simId}/report")
	public Result<SimDeviceView> report(@PathVariable("simId") String simId, @RequestBody Map<String, Object> body) {
		return Result.ok(simulatorService.report(simId, str(body, "type"), str(body, "json")));
	}

	/** 上下线（body: online=true|false） */
	@PostMapping("/devices/{simId}/lifecycle")
	public Result<SimDeviceView> lifecycle(@PathVariable("simId") String simId, @RequestBody Map<String, Object> body) {
		Object online = body.get("online");
		return Result.ok(simulatorService.lifecycle(simId,
				online instanceof Boolean ? (Boolean) online : Boolean.parseBoolean(String.valueOf(online))));
	}

	/** 手动应答命令（body: commandId, status=success|fail, result=JSON 字符串） */
	@PostMapping("/devices/{simId}/ack")
	public Result<SimDeviceView> ack(@PathVariable("simId") String simId, @RequestBody Map<String, Object> body) {
		return Result.ok(simulatorService.ack(simId, str(body, "commandId"), str(body, "status"), str(body, "result")));
	}

	private static String str(Map<String, Object> body, String key) {
		Object v = body.get(key);
		return v == null ? null : String.valueOf(v);
	}

	private static Long num(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(v));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
