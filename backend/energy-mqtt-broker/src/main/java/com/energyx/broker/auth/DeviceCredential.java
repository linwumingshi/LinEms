package com.energyx.broker.auth;

import com.energyx.common.enums.DeviceStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备凭据聚合模型（MySQL iot_device + iot_device_credential 联查，缓存于 cache:cred:{deviceKey}）。
 *
 * <p>
 * 缓存 JSON 即本类序列化结果，字段稳定，凭据变更时删除缓存强制重建。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCredential {

	/** clientId = {productKey}_{deviceName} */
	private String deviceKey;

	/** 设备主键 ID（iot_device.id） */
	private Long deviceId;

	/** 租户 ID */
	private Long tenantId;

	/** 产品密钥，{productKey}_{deviceName} 中的前半段，可含 '_'（平台锚点如 snd_ess_pcs） */
	private String productKey;

	/** 设备名，{productKey}_{deviceName} 中的后半段，禁止 '_'（SDK DeviceIdentity 约束） */
	private String deviceName;

	/** 设备主状态（DeviceStatus 枚举：OFFLINE 已激活 3 ONLINE 在线 4 DISABLED 禁用 5 BANNED 封禁） */
	private DeviceStatus deviceStatus;

	/** 凭据状态：1正常 2吊销 */
	private int authStatus;

	/** 设备密钥（HMAC-SHA256 签名密钥，敏感，禁止落日志） */
	private String deviceSecret;

	/** 凭据是否过期 */
	private boolean expired;

}
