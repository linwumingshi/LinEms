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

	/**
	 * 查询全部通知配置列表（按当前租户）。
	 * @return {@link Result}<{@link List}<{@link NotifyConfigRow}>> 通知配置列表
	 */
	@GetMapping("/configs")
	public Result<List<NotifyConfigRow>> configs() {
		return Result.ok(notifyService.listConfigs());
	}

	/**
	 * 新增通知配置。
	 * @param req 请求体，字段说明见 {@link NotifyConfigSaveReq}
	 * @return {@link Result}<{@link Long}> 新配置 ID
	 */
	@PostMapping("/config")
	public Result<Long> createConfig(@Valid @RequestBody NotifyConfigSaveReq req) {
		return Result.ok(notifyService.createConfig(req));
	}

	/**
	 * 修改通知配置。
	 * @param configId 配置 ID（路径变量）
	 * @param req 请求体，字段说明见 {@link NotifyConfigSaveReq}
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@PutMapping("/config/{configId}")
	public Result<Void> updateConfig(@PathVariable Long configId, @Valid @RequestBody NotifyConfigSaveReq req) {
		notifyService.updateConfig(configId, req);
		return Result.ok();
	}

	/**
	 * 删除通知配置。
	 * @param configId 配置 ID（路径变量）
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@DeleteMapping("/config/{configId}")
	public Result<Void> deleteConfig(@PathVariable Long configId) {
		notifyService.deleteConfig(configId);
		return Result.ok();
	}

	// ---------------- 模板 CRUD ----------------

	/**
	 * 查询通知模板列表，可按渠道筛选（按当前租户）。
	 * @param channel 渠道筛选（来源：查询参数，可选），取值见 {@link NotifyChannel}
	 * @return {@link Result}<{@link List}<{@link NotifyTemplateRow}>> 通知模板列表
	 */
	@GetMapping("/templates")
	public Result<List<NotifyTemplateRow>> templates(@RequestParam(required = false) String channel) {
		return Result.ok(notifyService.listTemplates(channel));
	}

	/**
	 * 新增通知模板。
	 * @param req 请求体，字段说明见 {@link NotifyTemplateSaveReq}
	 * @return {@link Result}<{@link Long}> 新模板 ID
	 */
	@PostMapping("/template")
	public Result<Long> createTemplate(@Valid @RequestBody NotifyTemplateSaveReq req) {
		return Result.ok(notifyService.createTemplate(req));
	}

	/**
	 * 修改通知模板。
	 * @param templateId 模板 ID（路径变量）
	 * @param req 请求体，字段说明见 {@link NotifyTemplateSaveReq}
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@PutMapping("/template/{templateId}")
	public Result<Void> updateTemplate(@PathVariable Long templateId, @Valid @RequestBody NotifyTemplateSaveReq req) {
		notifyService.updateTemplate(templateId, req);
		return Result.ok();
	}

	/**
	 * 删除通知模板。
	 * @param templateId 模板 ID（路径变量）
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@DeleteMapping("/template/{templateId}")
	public Result<Void> deleteTemplate(@PathVariable Long templateId) {
		notifyService.deleteTemplate(templateId);
		return Result.ok();
	}

	// ---------------- 发送与选项 ----------------

	/**
	 * 发送通知。按 configCode 定位渠道配置，按 templateCode 取模板渲染（title/content 非空时优先直接使用，
	 * 跳过模板），最终按渠道执行器发送；场景联动/告警/系统调用的统一入口。
	 * @param req 请求体，字段说明见 {@link NotifySendRequest}
	 * @return {@link Result}<{@link SendResult}> 发送结果（成功/失败 + 说明）
	 */
	@PostMapping("/send")
	public Result<SendResult> send(@Valid @RequestBody NotifySendRequest req) {
		return Result.ok(notifyService.send(req));
	}

	/**
	 * 渠道选项列表（前端表单下拉；每项含 code、label、supported）。
	 * @return {@link Result}<{@link List}<{@link Map}<{@link String}, {@link String}>>>
	 * 渠道选项， 每项包含 code（渠道码）、label（展示名）、supported（是否已实现发送能力）
	 */
	@GetMapping("/channels")
	public Result<List<Map<String, String>>> channels() {
		return Result.ok(Arrays.stream(NotifyChannel.values())
			.map(c -> Map.of("code", c.getCode(), "label", c.getLabel(), "supported", String.valueOf(c.supported())))
			.toList());
	}

}
