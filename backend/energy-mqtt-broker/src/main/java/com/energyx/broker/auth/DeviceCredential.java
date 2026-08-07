package com.energyx.broker.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备凭据聚合模型（MySQL iot_device + iot_device_credential 联查，缓存于 cache:cred:{deviceKey}）。
 *
 * <p>缓存 JSON 即本类序列化结果，字段稳定，凭据变更时删除缓存强制重建。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCredential {

    /** clientId = {productKey}_{deviceName} */
    private String deviceKey;

    private Long deviceId;

    private Long tenantId;

    private String productKey;

    private String deviceName;

    /** 设备主状态（Constants 常量：2已激活 3在线 4禁用 5封禁） */
    private int deviceStatus;

    /** 凭据状态：1正常 2吊销 */
    private int authStatus;

    private String deviceSecret;

    /** 凭据是否过期 */
    private boolean expired;
}
