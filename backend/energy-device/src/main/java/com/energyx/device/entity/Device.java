package com.energyx.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.energyx.common.entity.BaseEntity;
import com.energyx.common.enums.DeviceStatus;
import com.energyx.common.enums.DeviceType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备主表（统一设备树）。
 *
 * <p>
 * 储能资产全链路：储能柜(ENERGY_CABINET) → 电池簇(BATTERY_CLUSTER) → PCS / BMS / EMS / EDGE_GW。
 * 邻接表（parent_id）+ 物化路径（path）表达层级，path 约定 {@code /柜ID/簇ID/}，支持子树前缀查询。
 * </p>
 *
 * <p>
 * tenant_id 不显式赋值：插入时由条件化租户拦截器按当前请求上下文注入。
 * </p>
 */
@Getter
@Setter
@TableName("iot_device")
public class Device extends BaseEntity {

	/** 雪花 ID */
	@TableId(type = IdType.ASSIGN_ID)
	private Long deviceId;

	/** 所属企业 ID（企业维度归属） */
	private Long enterpriseId;

	/** 所属电站 */
	private Long stationId;

	/** 产品标识（认证与路由锚点），如 snd_ess_pcs */
	private String productKey;

	/** 设备名（不可含 _ 与 &；clientId = productKey_deviceName 契约，& 为认证 username 分隔符） */
	private String deviceName;

	/**
	 * 用户自定义显示名（可空，仅管理端展示用）。
	 *
	 * <p>
	 * 语义：deviceName 是设备 code（平台内唯一标识/接入协议锚点，创建后不可改）；displayName 可空可改可重复， 为空时前端回退显示设备
	 * code。
	 * </p>
	 */
	private String displayName;

	/**
	 * 设备类型（ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER），见
	 * {@link com.energyx.common.enums.DeviceType}
	 */
	private DeviceType deviceType;

	/** 父设备 ID（0=根） */
	private Long parentId;

	/** 物化路径，如 /柜ID/簇ID/，供子树前缀查询 */
	private String path;

	/** 设备树层级（根=1，逐级递增） */
	private Integer level;

	/** 同级排序号 */
	private Integer sort;

	/**
	 * 设备生命周期状态（UNREGISTERED/INACTIVE/OFFLINE/ONLINE/DISABLED/BANNED，对应 DB 0未注册 1未激活
	 * 2已激活(离线) 3在线 4禁用 5封禁）
	 */
	private DeviceStatus status;

	/** 固件版本号 */
	private String firmwareVersion;

	/** 接入协议，默认 MQTT */
	private String protocol;

	/** 当前连接的 Broker 节点（热数据，权威源在 Redis） */
	private String brokerNode;

	/** 最近一次上线时间 */
	private LocalDateTime lastOnlineTime;

	/** 最近一次离线时间 */
	private LocalDateTime lastOfflineTime;

	/** 累计在线秒数 */
	private Long onlineSeconds;

	/** 设备 MAC 地址 */
	private String mac;

	/** 设备 IP 地址 */
	private String ip;

	/** 子节点（仅树形接口填充，非表字段） */
	@TableField(exist = false)
	private List<Device> children;

}
