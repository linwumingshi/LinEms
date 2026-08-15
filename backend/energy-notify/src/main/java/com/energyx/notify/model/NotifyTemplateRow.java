package com.energyx.notify.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知模板行（iot_notify_template）。
 *
 * <p>
 * content_template 支持占位符 {@code ${xxx}}，发送时由上下文渲染；variables 为占位符说明 JSON
 * {@code [{"key":"deviceName","desc":"设备名称"}]}，供前端表单提示。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iot_notify_template")
public class NotifyTemplateRow extends BaseEntity {

	/** 模板ID（雪花，MyBatis-Plus ASSIGN_ID 自动生成） */
	@TableId(type = IdType.ASSIGN_ID)
	private Long templateId;

	/** 模板编码，租户内唯一 */
	private String templateCode;

	/** 模板名称 */
	private String templateName;

	/** 消息类型：ALARM/SCENE/DEVICE_EVENT/SYSTEM */
	private String messageType;

	/** 绑定渠道：WEBHOOK/WECOM/DINGTALK/EMAIL（与配置渠道一致才可发送） */
	private String channel;

	/** 标题模板（邮件主题等），支持 ${xxx} */
	private String titleTemplate;

	/** 内容模板，支持 ${xxx} */
	private String contentTemplate;

	/** 占位符说明 JSON */
	private String variables;

	/** 0停用 1启用 */
	private Integer status;

	private String description;

}
