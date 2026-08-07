package com.energyx.device.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备更新请求（仅更新非空字段）。
 */
@Data
public class DeviceUpdateReq {

    @Size(max = 128, message = "设备名长度不能超过 128")
    private String deviceName;

    @Size(max = 32, message = "设备类型长度不能超过 32")
    private String deviceType;

    private Long stationId;

    /** 设备状态：0未注册 1未激活 2已激活(离线) 3在线 4禁用 5封禁 */
    private Integer status;

    private String firmwareVersion;

    private String mac;

    private String ip;

    private Integer sort;
}
