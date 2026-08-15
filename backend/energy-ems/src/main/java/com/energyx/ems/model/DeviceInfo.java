package com.energyx.ems.model;

/**
 * 设备身份投影（跨服务调 energy-device 解析，替代跨 schema 直查 es_device.iot_device）。
 *
 * <p>
 * 只读身份字段（deviceId/tenantId/productKey/deviceName/status），供计划按电站解析 进线电表（METER）与 PCS
 * 下发目标；JSON 字段与 energy-device 的 Device 实体兼容。
 * </p>
 */
public record DeviceInfo(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
