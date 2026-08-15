package com.energyx.notify.web;

import com.energyx.common.model.Result;
import com.energyx.notify.channel.NotifyChannel;
import com.energyx.notify.channel.SendResult;
import com.energyx.notify.model.NotifyConfigRow;
import com.energyx.notify.model.NotifyTemplateRow;
import com.energyx.notify.service.NotifyService;
import com.energyx.notify.web.dto.NotifyConfigSaveReq;
import com.energyx.notify.web.dto.NotifySendRequest;
import com.energyx.notify.web.dto.NotifyTemplateSaveReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 消息通知 API（网关路由 /api/notify/** → energy-notify）。
 *
 * <ul>
 * <li>GET/POST/PUT/DELETE /api/notify/config(s) 通知配置 CRUD；</li>
 * <li>GET/POST/PUT/DELETE /api/notify/template(s) 通知模板 CRUD；</li>
 * <li>POST /api/notify/send 发送（场景联动/告警/系统调用入口）；</li>
 * <li>GET /api/notify/channels 渠道选项。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {

	private final NotifyService notifyService;

	public NotifyController(NotifyService notifyService) {
		this.notifyService = notifyService;
	}

	// ---------------- 配置 CRUD ----------------

	@GetMapping("/configs")
	public Result<List<NotifyConfigRow>> configs() {
		return Result.ok(notifyService.listConfigs());
	}

	@PostMapping("/config")
	public Result<Long> createConfig(@Valid @RequestBody NotifyConfigSaveReq req) {
		return Result.ok(notifyService.createConfig(req));
	}

	@PutMapping("/config/{configId}")
	public Result<Void> updateConfig(@PathVariable Long configId, @Valid @RequestBody NotifyConfigSaveReq req) {
		notifyService.updateConfig(configId, req);
		return Result.ok();
	}

	@DeleteMapping("/config/{configId}")
	public Result<Void> deleteConfig(@PathVariable Long configId) {
		notifyService.deleteConfig(configId);
		return Result.ok();
	}

	// ---------------- 模板 CRUD ----------------

	@GetMapping("/templates")
	public Result<List<NotifyTemplateRow>> templates(@RequestParam(required = false) String channel) {
		return Result.ok(notifyService.listTemplates(channel));
	}

	@PostMapping("/template")
	public Result<Long> createTemplate(@Valid @RequestBody NotifyTemplateSaveReq req) {
		return Result.ok(notifyService.createTemplate(req));
	}

	@PutMapping("/template/{templateId}")
	public Result<Void> updateTemplate(@PathVariable Long templateId, @Valid @RequestBody NotifyTemplateSaveReq req) {
		notifyService.updateTemplate(templateId, req);
		return Result.ok();
	}

	@DeleteMapping("/template/{templateId}")
	public Result<Void> deleteTemplate(@PathVariable Long templateId) {
		notifyService.deleteTemplate(templateId);
		return Result.ok();
	}

	// ---------------- 发送与选项 ----------------

	@PostMapping("/send")
	public Result<SendResult> send(@Valid @RequestBody NotifySendRequest req) {
		return Result.ok(notifyService.send(req));
	}

	/** 渠道选项（前端表单下拉；含 label） */
	@GetMapping("/channels")
	public Result<List<Map<String, String>>> channels() {
		return Result.ok(Arrays.stream(NotifyChannel.values())
			.map(c -> Map.of("code", c.getCode(), "label", c.getLabel(), "supported", String.valueOf(c.supported())))
			.toList());
	}

}
