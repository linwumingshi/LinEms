package com.energyx.device.web.dto;

import com.energyx.common.enums.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备创建请求。
 */
@Data
public class DeviceCreateReq {

	/**
	 * 设备名：禁止 _ 与 &（clientId = productKey_deviceName 契约，& 为认证 username 分隔符）。
	 *
	 */
	@NotBlank(message = "设备名不能为空")
	@Size(max = 128, message = "设备名长度不能超过 128")
	private String deviceName;

	/**
	 * 用户自定义显示名（可空，仅展示用；为空时前端回退显示设备 code）。
	 *
	 */
	@Size(max = 128, message = "显示名长度不能超过 128")
	private String displayName;

	/**
	 * 设备类型（ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER），见
	 * {@link DeviceType}。
	 *
	 */
	@NotNull(message = "设备类型不能为空")
	private DeviceType deviceType;

	/**
	 * 产品标识（认证与路由锚点，如 snd_ess_pcs，长度上限 64）。
	 *
	 */
	@NotBlank(message = "产品标识不能为空")
	@Size(max = 64, message = "产品标识长度不能超过 64")
	private String productKey;

	/** 父设备 ID（空或 0 = 根节点） */
	private Long parentId;

	/** 所属电站 ID */
	private Long stationId;

	/** 所属企业 ID */
	private Long enterpriseId;

	/** 固件版本号 */
	private String firmwareVersion;

	/** 设备 MAC 地址 */
	private String mac;

	/** 设备 IP 地址 */
	private String ip;

	/** 同级排序号 */
	private Integer sort;

	/** 接入协议，默认 MQTT */
	private String protocol;

}
