package com.energyx.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警事件消息（Kafka iot-alarm，key=deviceId）。
 *
 * <p>
 * 由告警服务（energy-alarm）在规则命中/恢复时产出，消费方：ws-pusher（驾驶舱实时弹窗）、 通知中心（短信/邮件）、运营大屏、ES 日志落库。status
 * 取值：ACTIVE（触发）/ RECOVERED（恢复）。
 * </p>
 *
 * <p>
 * ACK（人工确认）不进消息总线——记录状态变更由 REST 直改 iot_alarm_record，告警查询以 DB 为准， 避免与规则引擎的状态机耦合。
 * </p>
 */
@Data
public class AlarmMessage {

	/** 告警事件 ID（雪花，幂等锚点，与 iot_alarm_record.alarm_event_id 一致） */
	private String alarmEventId;

	private Long tenantId;

	private Long deviceId;

	private String productKey;

	private Long ruleId;

	private String ruleCode;

	/** 告警级别 1提示 2一般 3严重 4危急 */
	private Integer level;

	/** 触发类型 1属性比较 2事件 3策略 */
	private Integer type;

	/** ACTIVE（触发）/ RECOVERED（恢复） */
	private String status;

	/** 告警内容 */
	private String message;

	/** 扩展：当前值/阈值/事件数据等 */
	private Map<String, Object> ext = new LinkedHashMap<>();

	/** 事件时间（毫秒） */
	private Long ts;

}
