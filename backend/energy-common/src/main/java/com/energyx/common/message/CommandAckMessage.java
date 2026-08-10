package com.energyx.common.message;

import lombok.Data;

import java.util.Map;

/**
 * 指令 ACK 消息（Kafka iot-command-ack，key=commandId）。
 *
 * <p>
 * 由接入适配（energy-access）解析设备 up/ack 报文后产出，消费方：Command Center （energy-command）状态机流转
 * CREATED→SENT→DEVICE_RECEIVED→EXECUTING→SUCCESS/FAILED/TIMEOUT。
 * </p>
 */
@Data
public class CommandAckMessage {

	/** 指令 ID（平台生成，回写状态机锚点） */
	private String commandId;

	private Long deviceId;

	/** 指令状态：DEVICE_RECEIVED | EXECUTING | SUCCESS | FAILED | TIMEOUT */
	private String status;

	/** 失败错误码（status=FAILED 时携带） */
	private String errorCode;

	/** 执行结果载荷（可选，如设置后的实际功率） */
	private Map<String, Object> result;

	/** ACK 时间（毫秒） */
	private Long ts;

}
