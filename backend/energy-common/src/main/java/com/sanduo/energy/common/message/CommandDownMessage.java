package com.sanduo.energy.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下行指令消息（Kafka iot-command-down，key=deviceId）。
 *
 * <p>由 Command Center（energy-command，Phase 6）产出，接入适配（energy-access）消费后
 * 桥接为 mqtt.router PUBLISH 信封下发到设备订阅的 `{pk}/{dn}/down/command`。
 * 设备离线时该 JSON 也会写入 Redis `iot:cmd:q:{deviceId}`，设备上线（lifecycle 事件）触发补发。</p>
 */
@Data
public class CommandDownMessage {

    /** 指令 ID（幂等锚点） */
    private String commandId;

    private Long deviceId;
    private Long tenantId;
    private String productKey;
    private String deviceName;

    /** 物模型服务标识，如 setPower */
    private String command;

    /** 指令参数 */
    private Map<String, Object> params = new LinkedHashMap<>();

    /** 下发 QoS，默认 1（储能控制链路至少 QoS1，ADR-009） */
    private Integer qos = 1;

    /** 下发时间（毫秒） */
    private Long ts;
}
