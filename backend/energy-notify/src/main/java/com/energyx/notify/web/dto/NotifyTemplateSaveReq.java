package com.energyx.notify.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通知模板新增/修改请求体（POST/PUT /api/notify/template）。
 *
 * <p>
 * title/content_template 支持占位符 {@code ${xxx}}（如 ${deviceName} ${ruleName} ${value}
 * ${ts}）； variables 为占位符说明 JSON
 * {@code [{"key":"deviceName","desc":"设备名称"}]}，仅供前端表单提示，不参与渲染。 template_code 创建后不可改。
 * </p>
 */
@Data
public class NotifyTemplateSaveReq {

	/** 租户 ID（缺省 1，单租户环境） */
	private Long tenantId;

	/**
	 * 模板编码，租户内唯一
	 * @required
	 */
	@NotBlank(message = "templateCode 不能为空")
	private String templateCode;

	/**
	 * 模板名称
	 * @required
	 */
	@NotBlank(message = "templateName 不能为空")
	private String templateName;

	/**
	 * 消息类型：ALARM/SCENE/DEVICE_EVENT/SYSTEM
	 * @required
	 */
	@NotBlank(message = "messageType 不能为空")
	private String messageType;

	/**
	 * 绑定渠道，取值见 {@link NotifyChannel}（与配置渠道一致才可发送）
	 * @required
	 */
	@NotBlank(message = "channel 不能为空")
	private String channel;

	/** 标题模板（可空，支持 ${xxx}） */
	private String titleTemplate;

	/**
	 * 内容模板（必填，支持 ${xxx}）
	 * @required
	 */
	@NotBlank(message = "contentTemplate 不能为空")
	private String contentTemplate;

	/** 占位符说明 JSON（可空，供前端表单提示） */
	private String variables;

	/** 状态：0停用 1启用（缺省 1） */
	@Min(value = 0, message = "status 仅 0/1")
	@Max(value = 1, message = "status 仅 0/1")
	private Integer status;

	/** 描述（可空） */
	private String description;

}
