package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略定义（ems_strategy）。表无 deleted 列、PK 为 strategy_id，
 * 故不 extends BaseEntity；审计字段由 AuditMetaObjectHandler 填充。
 */
@Data
@TableName("ems_strategy")
public class EmsStrategy {

    @TableId(type = IdType.AUTO)
    private Long strategyId;

    private Long tenantId;

    private Long stationId;

    private String strategyName;

    /** PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME */
    private String strategyType;

    /** 策略配置 JSON（chargeWindows/dischargeWindows/socRange） */
    private String config;

    /** 多策略冲突仲裁优先级 */
    private Integer priority;

    /** 0草稿 1启用 2停用 */
    private Integer status;

    private Integer version;

    private Long createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
