package com.energyx.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化属性上报消息（Kafka iot-thing-property，key=deviceId）。
 *
 * <p>由接入适配（energy-access）在物模型校验+类型强转后产出，消费方：
 * tsdb-writer（TDengine 宽表）、shadow-updater（影子 reported）、rule-engine、
 * ws-pusher、ai-feature。同一 deviceId 的 key 保证写入同一分区，单设备有序。</p>
 *
 * <p>{@code properties} 为 {@link LinkedHashMap}：键=物模型 identifier，
 * 顺序=物模型声明顺序（供 TDengine 宽表列对齐），值已按物模型 dataType 强转。</p>
 */
@Data
public class ThingPropertyMessage {

    /** 消息 ID（设备自带或接入生成），幂等去重键 */
    private String messageId;

    private Long deviceId;
    private Long tenantId;
    private Long enterpriseId;
    private Long stationId;
    private String productKey;

    /** 上报类型：report（周期/变化上报）| setReply（属性设置应答） */
    private String dataType = "report";

    /** 设备采集时间（毫秒） */
    private Long ts;

    /** 属性键值：identifier → 已强转值（float/int/bool/enum/string） */
    private Map<String, Object> properties = new LinkedHashMap<>();
}
