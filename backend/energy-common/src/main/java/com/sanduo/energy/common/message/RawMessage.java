package com.sanduo.energy.common.message;

import lombok.Data;

/**
 * 原始报文留痕（Kafka iot-raw，key=messageId）。
 *
 * <p>由接入适配（energy-access）在解析前无条件产出，保留设备原始字节（base64），
 * 用于追踪（链路排查）、补数（重放重建时序/影子）、审计。拒绝（校验失败/设备不存在）
 * 的报文也留痕并标注 rejectReason，不静默丢弃。</p>
 */
@Data
public class RawMessage {

    private String messageId;

    /** 设备键 {productKey}_{deviceName} */
    private String deviceKey;

    /** 解析出设备后的 deviceId；解析失败为 null */
    private Long deviceId;

    /** 原始 MQTT Topic（含 up/{type}） */
    private String topic;

    private Integer qos;
    private Boolean retain;

    /** 到达时间（毫秒） */
    private Long ts;

    /** 原始报文 base64 */
    private String payloadBase64;

    /** 拒绝原因（null=已接受进入标准化流程） */
    private String rejectReason;
}
