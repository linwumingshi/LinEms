package com.energyx.notify.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知配置行（iot_notify_config）。
 *
 * <p>
 * channel_config 为 JSON 字符串：
 * WEBHOOK={url,headers}、WECOM={webhook}、DINGTALK={webhook,secret}、
 * EMAIL={host,port,username,password,from,ssl}（短信/语音预留）。
 * </p>
 */
@Data
public class NotifyConfigRow {

	/** 配置ID（雪花） */
	private Long configId;

	private Long tenantId;

	/** 配置编码，租户内唯一，如 WEBHOOK_OPS */
	private String configCode;

	/** 配置名称 */
	private String configName;

	/** 渠道：WEBHOOK/WECOM/DINGTALK/EMAIL */
	private String channel;

	/** 渠道配置 JSON */
	private String channelConfig;

	/** 0停用 1启用 */
	private Integer status;

	private String description;

	private Long createBy;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

}
