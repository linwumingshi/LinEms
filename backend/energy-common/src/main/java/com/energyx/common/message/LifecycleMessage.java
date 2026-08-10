package com.energyx.common.message;

import lombok.Data;

/**
 * 设备生命周期消息（Kafka iot-device-lifecycle，key=deviceId）。
 *
 * <p>
 * 两个生产者：
 * <ul>
 * <li>Broker（权威）：连接建立/断开时按心跳判定发出（ONLINE/OFFLINE）；</li>
 * <li>接入适配：设备 up/lifecycle 自报（重启/断网自报）转译后发出。</li>
 * </ul>
 * 消费方：energy-access（刷新 iot_device 状态 + 上下线记录 + 离线指令补发）、energy-device、energy-shadow。
 * </p>
 */
@Data
public class LifecycleMessage {

	/** ONLINE | OFFLINE */
	private String eventType;

	private Long deviceId;

	private Long tenantId;

	private String productKey;

	private String deviceName;

	/** 连接所在的 Broker 节点 */
	private String brokerNode;

	/** 设备源 IP */
	private String ip;

	/** 离线原因：NORMAL/HEARTBEAT_TIMEOUT/DUPLICATE_CLIENT/KICK/DEVICE_SELF */
	private String reason;

	/** 事件时间（毫秒） */
	private Long ts;

}
