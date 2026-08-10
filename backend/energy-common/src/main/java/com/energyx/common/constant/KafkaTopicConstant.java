package com.energyx.common.constant;

/**
 * Kafka Topic 定稿（与 Phase2 §5 的 15 个 topic 一一对应）。
 */
public final class KafkaTopicConstant {

	private KafkaTopicConstant() {
	}

	/** 原始报文（追踪/补数） */
	public static final String IOT_RAW = "iot-raw";

	/** 标准化属性上报 */
	public static final String IOT_THING_PROPERTY = "iot-thing-property";

	/** 设备事件（告警/故障） */
	public static final String IOT_THING_EVENT = "iot-thing-event";

	/** 设备上下线 */
	public static final String IOT_DEVICE_LIFECYCLE = "iot-device-lifecycle";

	/** 设备注册 */
	public static final String IOT_DEVICE_REGISTER = "iot-device-register";

	/** 下行指令 */
	public static final String IOT_COMMAND_DOWN = "iot-command-down";

	/** 指令 ACK */
	public static final String IOT_COMMAND_ACK = "iot-command-ack";

	/** 告警事件 */
	public static final String IOT_ALARM = "iot-alarm";

	/** 影子差异 */
	public static final String IOT_SHADOW_DELTA = "iot-shadow-delta";

	/** 策略输出计划 */
	public static final String EMS_PLAN = "ems-plan";

	/** 操作审计 */
	public static final String IOT_AUDIT = "iot-audit";

	/** Broker 跨节点消息路由（阶段 2 起为兼容期通道，仅多版本混布时启用） */
	public static final String MQTT_ROUTER = "mqtt.router";

	/** 设备上行（阶段 2：Broker 唯一生产者，唯一消费组 energy-access-uplink 摄取） */
	public static final String MQTT_UPLINK = "mqtt.uplink";

	/** 下行定向 topic 前缀：mqtt.down.{nodeId}，仅目标节点消费（投递目标 = mqtt:conn owner） */
	public static final String MQTT_DOWN_PREFIX = "mqtt.down.";

	/** 跨节点广播（KICK 踢线 / owner 解析失败回落）；每节点唯一消费组全量 fan-out */
	public static final String MQTT_BROADCAST = "mqtt.broadcast";

	/** 日志（→ES） */
	public static final String IOT_LOG = "iot-log";

	/** AI 特征 */
	public static final String IOT_AI_FEATURE = "iot-ai-feature";

	/** 通知 */
	public static final String IOT_NOTIFY = "iot-notify";

	/** 死信队列（基础设施 topic，非业务 topic：消费失败且重试耗尽后的兜底落点） */
	public static final String IOT_DLQ = "iot-dlq";

}
