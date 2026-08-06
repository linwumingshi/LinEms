package com.sanduo.energy.command.model;

/**
 * 设备信息投影（跨 schema 查询 es_device.iot_device，供指令解析 pk/dn/租户）。
 */
public record DeviceInfo(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
