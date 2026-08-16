package com.energyx.notify.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通知配置新增/修改请求体（POST/PUT /api/notify/config）。
 *
 * <p>
 * channel_config 为 JSON 字符串，按渠道结构： WEBHOOK={url,headers}、WECOM={webhook}、
 * DINGTALK={webhook,secret}、EMAIL={host,port,username,password,from,ssl}。config_code
 * 创建后不可改。
 * </p>
 */
@Data
public class NotifyConfigSaveReq {

	/** 租户 ID（缺省 1，单租户环境） */
	private Long tenantId;

	/**
	 * 配置编码，租户内唯一，如 WEBHOOK_OPS
	 */
	@NotBlank(message = "configCode 不能为空")
	private String configCode;

	/**
	 * 配置名称
	 */
	@NotBlank(message = "configName 不能为空")
	private String configName;

	/**
	 * 渠道，取值见 {@link NotifyChannel}：WEBHOOK/WECOM/DINGTALK/EMAIL
	 */
	@NotBlank(message = "channel 不能为空")
	private String channel;

	/**
	 * 渠道配置 JSON 字符串，按渠道结构（见类注释）
	 */
	@NotBlank(message = "channelConfig 不能为空")
	private String channelConfig;

	/** 状态：0停用 1启用（缺省 1） */
	@Min(value = 0, message = "status 仅 0/1")
	@Max(value = 1, message = "status 仅 0/1")
	private Integer status;

	/** 描述（可空） */
	private String description;

}
