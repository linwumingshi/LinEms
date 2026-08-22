package com.energyx.broker.auth;

import com.energyx.common.enums.DeviceStatus;

/**
 * iot_device 最小查询投影（认证所需字段）。各字段含义：
 * <ul>
 * <li>deviceId：设备主键 ID；</li>
 * <li>tenantId：租户 ID；</li>
 * <li>productKey：产品密钥，{productKey}_{deviceName} 中的前半段，可含 '_'（平台锚点如 snd_ess_pcs）；</li>
 * <li>deviceName：设备名，{productKey}_{deviceName} 中的后半段，禁止 '_'；</li>
 * <li>status：设备主状态（DeviceStatus：2 已激活 / 3 在线 / 4 禁用 / 5 封禁）。</li>
 * </ul>
 */
public record DeviceRow(Long deviceId, Long tenantId, String productKey, String deviceName, DeviceStatus status) {
}
