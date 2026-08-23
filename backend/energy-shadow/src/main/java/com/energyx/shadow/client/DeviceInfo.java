package com.energyx.shadow.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 设备信息投影（跨 schema 查询 es_device.iot_device，供 desired 校验解析 productKey）。
 *
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}：device 服务返回完整 {@code Device} 实体
 * （含大量字段），本投影仅取 5 个关键字段；不忽略未知字段时 Jackson 会在 Feign 反序列化 {@code Result<DeviceInfo>} 时抛
 * UnrecognizedPropertyException。与 energy-command 的 DeviceInfo 投影同构（模块边界不允许跨模块复用，建最小投影）。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInfo(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
