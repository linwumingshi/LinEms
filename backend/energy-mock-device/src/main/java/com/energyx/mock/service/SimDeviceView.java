package com.energyx.mock.service;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 模拟设备快照视图（REST/WS 返回前端）。
 */
@Data
public class SimDeviceView {

	/** 设备标识 = clientId = {productKey}_{deviceName} */
	private String simId;

	private String productKey;

	private String deviceName;

	/** 平台设备主键（自动建档时已知） */
	private Long deviceId;

	/** true=自动建档；false=接管已有设备 */
	private boolean autoProvisioned;

	/** MQTT 是否已连接 broker */
	private boolean connected;

	/** 是否上报过上线 */
	private boolean online;

	/** 是否正在发起连接（CONNACK 未达），用于前端展示"连接中" */
	private boolean connecting;

	/** 最近一次连接失败原因 */
	private String lastError;

	/** 聚合连接态：ONLINE / CONNECTED / CONNECTING / FAILED / OFFLINE */
	private String status;

	/** 最近日志（bounded 80） */
	private List<Map<String, Object>> recentLogs;

	/** 待应答命令 */
	private List<Map<String, Object>> pendingCommands;

}
