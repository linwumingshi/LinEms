package com.sanduo.energy.device.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备创建请求。
 */
@Data
public class DeviceCreateReq {

    /** 设备名：禁止 _ 与 &（clientId = productKey_deviceName 契约，& 为认证 username 分隔符） */
    @NotBlank(message = "设备名不能为空")
    @Size(max = 128, message = "设备名长度不能超过 128")
    private String deviceName;

    /** ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW */
    @NotBlank(message = "设备类型不能为空")
    @Size(max = 32, message = "设备类型长度不能超过 32")
    private String deviceType;

    @NotBlank(message = "产品标识不能为空")
    @Size(max = 64, message = "产品标识长度不能超过 64")
    private String productKey;

    /** 父设备 ID（空或 0 = 根节点） */
    private Long parentId;

    private Long stationId;

    private Long enterpriseId;

    private String firmwareVersion;

    private String mac;

    private String ip;

    private Integer sort;

    /** 默认 0 未注册 */
    private Integer status;

    /** 默认 MQTT */
    private String protocol;
}
