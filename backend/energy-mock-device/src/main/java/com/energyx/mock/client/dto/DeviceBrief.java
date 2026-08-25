package com.energyx.mock.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备简述（仅取主键），用于模拟器 upsert 查重时反序列化 device 服务 {@code /by-name} 响应。
 *
 * <p>
 * device 服务返回的是完整 {@code Device} 实体（含 BaseEntity 的 tenantId/createTime/deleted 等字段），
 * 此处忽略未知字段、只绑定主键。
 * </p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceBrief {

	/** 设备主键（雪花 ID，与 Device.deviceId 一致） */
	private Long deviceId;

}
