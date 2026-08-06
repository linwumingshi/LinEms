package com.sanduo.energy.common.message;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子差异消息（Kafka iot-shadow-delta，key=deviceId）。
 *
 * <p>由影子服务（energy-shadow）在 desired 与 reported 出现差异时产出，消费方：
 * edge-sync/命令桥接（把差异属性转换为设备 set 指令下发）与 ws-pusher（驾驶舱实时刷新）。</p>
 */
@Data
public class ShadowDeltaMessage {

    private Long deviceId;
    private Long tenantId;

    /** 期望版本（desired 乐观锁版本） */
    private Integer version;

    /** 待同步差异：属性 identifier → 目标值（仅 desired ≠ reported 的键） */
    private Map<String, Object> desired = new LinkedHashMap<>();

    /** 差异产生时间（毫秒） */
    private Long ts;
}
