package com.energyx.notify.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.notify.channel.ChannelExecutor;
import com.energyx.notify.channel.NotifyChannel;
import com.energyx.notify.channel.SendResult;
import com.energyx.notify.mapper.NotifyConfigMapper;
import com.energyx.notify.mapper.NotifyTemplateMapper;
import com.energyx.notify.model.NotifyConfigRow;
import com.energyx.notify.model.NotifyTemplateRow;
import com.energyx.notify.util.TemplateRenderer;
import com.energyx.notify.web.dto.NotifyConfigSaveReq;
import com.energyx.notify.web.dto.NotifySendRequest;
import com.energyx.notify.web.dto.NotifyTemplateSaveReq;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知服务：配置/模板 CRUD + 发送编排。
 *
 * <p>
 * 发送流程：configCode 定位渠道配置 → templateCode 取模板（content 非空则跳过模板）→ 占位符渲染 → 按渠道分发到对应执行器。渠道执行器通过
 * Spring 注入的 {@code Map<String, ChannelExecutor>} 自动装配（key = channel()）。
 * </p>
 */
@Service
public class NotifyService {

	private static final long DEFAULT_TENANT = 1L;

	private final NotifyConfigMapper configMapper;

	private final NotifyTemplateMapper templateMapper;

	private final TemplateRenderer renderer;

	private final Map<String, ChannelExecutor> executors;

	public NotifyService(NotifyConfigMapper configMapper, NotifyTemplateMapper templateMapper,
			TemplateRenderer renderer, Map<String, ChannelExecutor> executors) {
		this.configMapper = configMapper;
		this.templateMapper = templateMapper;
		this.renderer = renderer;
		this.executors = executors;
	}

	// ---------------- 配置 CRUD ----------------

	public List<NotifyConfigRow> listConfigs() {
		return configMapper.selectList(DEFAULT_TENANT);
	}

	public Long createConfig(NotifyConfigSaveReq req) {
		if (configMapper.selectByCode(DEFAULT_TENANT, req.getConfigCode().trim()) != null) {
			throw new BusinessException(40000, "配置编码已存在: " + req.getConfigCode());
		}
		NotifyConfigRow row = new NotifyConfigRow();
		row.setTenantId(DEFAULT_TENANT);
		row.setConfigCode(req.getConfigCode().trim());
		row.setConfigName(req.getConfigName().trim());
		row.setChannel(req.getChannel().toUpperCase());
		row.setChannelConfig(req.getChannelConfig());
		row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
		row.setDescription(req.getDescription());
		row.setCreateBy(0L);
		configMapper.insert(row);
		return row.getConfigId();
	}

	public void updateConfig(Long configId, NotifyConfigSaveReq req) {
		NotifyConfigRow row = configMapper.selectById(configId);
		if (row == null)
			throw new BusinessException(40400, "通知配置不存在");
		row.setConfigName(req.getConfigName().trim());
		row.setChannel(req.getChannel().toUpperCase());
		row.setChannelConfig(req.getChannelConfig());
		row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
		row.setDescription(req.getDescription());
		if (configMapper.updateById(row) == 0)
			throw new BusinessException(40400, "通知配置不存在");
	}

	public void deleteConfig(Long configId) {
		if (configMapper.deleteById(configId, DEFAULT_TENANT) == 0) {
			throw new BusinessException(40400, "通知配置不存在");
		}
	}

	// ---------------- 模板 CRUD ----------------

	public List<NotifyTemplateRow> listTemplates(String channel) {
		if (channel != null && !channel.isBlank()) {
			return templateMapper.selectByChannel(DEFAULT_TENANT, channel.toUpperCase());
		}
		return templateMapper.selectList(DEFAULT_TENANT);
	}

	public Long createTemplate(NotifyTemplateSaveReq req) {
		if (templateMapper.selectByCode(DEFAULT_TENANT, req.getTemplateCode().trim()) != null) {
			throw new BusinessException(40000, "模板编码已存在: " + req.getTemplateCode());
		}
		NotifyTemplateRow row = new NotifyTemplateRow();
		row.setTenantId(DEFAULT_TENANT);
		row.setTemplateCode(req.getTemplateCode().trim());
		row.setTemplateName(req.getTemplateName().trim());
		row.setMessageType(req.getMessageType().toUpperCase());
		row.setChannel(req.getChannel().toUpperCase());
		row.setTitleTemplate(req.getTitleTemplate());
		row.setContentTemplate(req.getContentTemplate());
		row.setVariables(req.getVariables());
		row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
		row.setDescription(req.getDescription());
		row.setCreateBy(0L);
		templateMapper.insert(row);
		return row.getTemplateId();
	}

	public void updateTemplate(Long templateId, NotifyTemplateSaveReq req) {
		NotifyTemplateRow row = templateMapper.selectById(templateId);
		if (row == null)
			throw new BusinessException(40400, "通知模板不存在");
		row.setTemplateName(req.getTemplateName().trim());
		row.setMessageType(req.getMessageType().toUpperCase());
		row.setChannel(req.getChannel().toUpperCase());
		row.setTitleTemplate(req.getTitleTemplate());
		row.setContentTemplate(req.getContentTemplate());
		row.setVariables(req.getVariables());
		row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
		row.setDescription(req.getDescription());
		if (templateMapper.updateById(row) == 0)
			throw new BusinessException(40400, "通知模板不存在");
	}

	public void deleteTemplate(Long templateId) {
		if (templateMapper.deleteById(templateId, DEFAULT_TENANT) == 0) {
			throw new BusinessException(40400, "通知模板不存在");
		}
	}

	// ---------------- 发送 ----------------

	/**
	 * 发送通知（场景联动/告警/系统调用入口）。
	 * @return 渠道发送结果（成功/失败 + 说明）
	 */
	public SendResult send(NotifySendRequest req) {
		NotifyConfigRow config = configMapper.selectByCode(DEFAULT_TENANT, req.getConfigCode().trim());
		if (config == null) {
			throw new BusinessException(40400, "通知配置不存在: " + req.getConfigCode());
		}
		if (config.getStatus() != null && config.getStatus() != 1) {
			return SendResult.fail("通知配置已停用: " + config.getConfigCode());
		}
		NotifyChannel channel = parseChannel(config.getChannel());
		if (!channel.supported()) {
			return SendResult.fail("渠道未实现发送: " + channel.getCode() + "（" + channel.getLabel() + "）");
		}
		Map<String, Object> ctx = req.getContext() == null ? new HashMap<>() : req.getContext();
		String title = req.getTitle();
		String content = req.getContent();
		// content 为空时按模板渲染
		if (content == null || content.isBlank()) {
			if (req.getTemplateCode() == null || req.getTemplateCode().isBlank()) {
				throw new BusinessException(40000, "content 与 templateCode 至少提供一个");
			}
			NotifyTemplateRow template = templateMapper.selectByCode(DEFAULT_TENANT, req.getTemplateCode().trim());
			if (template == null) {
				throw new BusinessException(40400, "通知模板不存在: " + req.getTemplateCode());
			}
			if (template.getStatus() != null && template.getStatus() != 1) {
				return SendResult.fail("通知模板已停用: " + template.getTemplateCode());
			}
			if (!template.getChannel().equalsIgnoreCase(channel.getCode())) {
				throw new BusinessException(40000,
						"模板渠道与配置渠道不一致：模板=" + template.getChannel() + " 配置=" + channel.getCode());
			}
			title = renderer.render(title != null ? title : template.getTitleTemplate(), ctx);
			content = renderer.render(template.getContentTemplate(), ctx);
		}
		else {
			title = renderer.render(title, ctx);
			content = renderer.render(content, ctx);
		}
		// Map 注入的 key 是 bean 名（WebhookExecutor），需按 channel() 遍历匹配
		ChannelExecutor executor = executors.values()
			.stream()
			.filter(e -> e.channel().equals(channel.getCode()))
			.findFirst()
			.orElse(null);
		if (executor == null) {
			return SendResult.fail("渠道执行器未注册: " + channel.getCode());
		}
		return executor.send(config, title, content);
	}

	private static NotifyChannel parseChannel(String code) {
		for (NotifyChannel c : NotifyChannel.values()) {
			if (c.getCode().equalsIgnoreCase(code))
				return c;
		}
		throw new BusinessException(40000, "未知渠道: " + code);
	}

}
