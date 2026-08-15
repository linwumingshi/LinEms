package com.energyx.notify.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
@EqualsAndHashCode(callSuper = true)
@TableName("iot_notify_config")
public class NotifyConfigRow extends BaseEntity {

	/** 配置ID（雪花，MyBatis-Plus ASSIGN_ID 自动生成） */
	@TableId(type = IdType.ASSIGN_ID)
	private Long configId;

	/** 创建人（用户 ID，系统动作填 0；表含 create_by 列） */
	private Long createBy;

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

}
