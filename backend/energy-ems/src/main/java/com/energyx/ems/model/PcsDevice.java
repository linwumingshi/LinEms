package com.energyx.ems.model;

/**
 * PCS 下发设备投影（跨 schema 读 es_device.iot_device，供计划按电站解析下发目标）。
 *
 * <p>
 * 只读身份字段（deviceId/tenantId/productKey/deviceName/status），不建冗余副本（P0-2：下发设备从设备表解析， 支持一电站多
 * PCS，执行记录 device_id 取真实设备）。
 * </p>
 */
public record PcsDevice(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
