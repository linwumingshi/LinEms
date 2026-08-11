package com.energyx.ems.model;

/** 站内进线电能表（es_device.iot_device，device_type=METER，跨库只读投影）。 */
public record MeterDevice(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
