package com.energyx.command.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 设备信息投影（跨 schema 查询 es_device.iot_device，供指令解析 pk/dn/租户）。
 *
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}：device 服务返回的是完整 {@code Device} 实体（含
 * createTime/updateTime/deleted/parentId 等大量字段），而本投影仅取 5 个关键字段。 若不忽略未知字段， Jackson 默认
 * FAIL_ON_UNKNOWN_PROPERTIES 会在 Feign 反序列化 {@code Result<DeviceInfo>} 时抛
 * UnrecognizedPropertyException，导致指令创建失败。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInfo(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
