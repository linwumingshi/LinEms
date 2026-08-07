package com.sanduo.energy.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sanduo.energy.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备主表（统一设备树）。
 *
 * <p>储能资产全链路：储能柜(ENERGY_CABINET) → 电池簇(BATTERY_CLUSTER) → PCS / BMS / EMS / EDGE_GW。
 * 邻接表（parent_id）+ 物化路径（path）表达层级，path 约定 {@code /柜ID/簇ID/}，支持子树前缀查询。</p>
 *
 * <p>tenant_id 不显式赋值：插入时由条件化租户拦截器按当前请求上下文注入。</p>
 */
@Getter
@Setter
@TableName("iot_device")
public class Device extends BaseEntity {

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long deviceId;

    private Long tenantId;

    private Long enterpriseId;

    /** 所属电站 */
    private Long stationId;

    /** 产品标识（认证与路由锚点），如 snd_ess_pcs */
    private String productKey;

    private String deviceName;

    /** ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW */
    private String deviceType;

    /** 父设备 ID（0=根） */
    private Long parentId;

    /** 物化路径，如 /柜ID/簇ID/，供子树查询 */
    private String path;

    /** 设备树层级 */
    private Integer level;

    private Integer sort;

    /** 设备状态：0未注册 1未激活 2已激活(离线) 3在线 4禁用 5封禁 */
    private Integer status;

    private String firmwareVersion;

    /** 接入协议，默认 MQTT */
    private String protocol;

    /** 当前连接的 Broker 节点（热数据，权威源在 Redis） */
    private String brokerNode;

    private LocalDateTime lastOnlineTime;

    private LocalDateTime lastOfflineTime;

    /** 累计在线秒数 */
    private Long onlineSeconds;

    private String mac;

    private String ip;

    /** 子节点（仅树形接口填充，非表字段） */
    @TableField(exist = false)
    private List<Device> children;
}
