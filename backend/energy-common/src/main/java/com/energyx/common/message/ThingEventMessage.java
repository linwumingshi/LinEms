package com.energyx.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化设备事件消息（Kafka iot-thing-event，key=deviceId）。
 *
 * <p>事件量远小于属性，统一投递；消费方：alarm（规则检测）、tsdb-writer（st_event 落库）、ws-pusher。
 * severity 对齐 TDengine 定义：1提示 2一般 3严重 4危急，默认由物模型事件类型映射
 * （INFO→1 / WARN→2 / ERROR→3），设备显式携带时以设备值为准。</p>
 */
@Data
public class ThingEventMessage {

    /** 消息 ID（幂等去重键） */
    private String messageId;

    /** 事件实例 ID（设备生成，去重/追踪用） */
    private String eventId;

    private Long deviceId;
    private Long tenantId;
    private Long enterpriseId;
    private Long stationId;
    private String productKey;

    /** 物模型事件标识，如 overTemp */
    private String eventName;

    /** 严重级别 1提示 2一般 3严重 4危急 */
    private Integer severity;

    /** 事件码（可选，如 ALM_TEMP_HIGH） */
    private String code;

    /** 事件发生时间（毫秒） */
    private Long ts;

    /** 可变事件载荷 */
    private Map<String, Object> data = new LinkedHashMap<>();
}
