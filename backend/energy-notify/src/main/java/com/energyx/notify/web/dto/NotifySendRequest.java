package com.energyx.notify.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 通知发送请求体（POST /api/notify/send，场景联动/告警/系统调用入口）。
 *
 * <p>
 * 按 configCode 定位渠道配置，按 templateCode 取模板渲染（title/content 非空时优先直接使用，跳过模板），
 * 最终按渠道执行器发送。context 为占位符上下文，如 {@code {"deviceName":"sim-dev-000001","value":"60"}}。
 * </p>
 */
@Data
public class NotifySendRequest {

	/** 租户 ID（缺省 1，单租户环境） */
	private Long tenantId;

	/**
	 * 通知配置编码（必填），用于定位渠道配置
	 */
	@NotBlank(message = "configCode 不能为空")
	private String configCode;

	/** 通知模板编码（与 configCode 渠道一致；content 非空时可省略） */
	private String templateCode;

	/** 直接内容（非空时跳过模板渲染，title 一并生效） */
	private String content;

	/** 标题（邮件主题/企微标题等，可空） */
	private String title;

	/** 占位符上下文，键为占位符名、值为替换值，如 {"deviceName":"sim-dev-000001","value":"60"} */
	private Map<String, Object> context;

}
