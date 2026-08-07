package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 安全约束（ems_constraint）。下发前安全包络校验，Phase1 §2.4。 */
@Data
@TableName("ems_constraint")
public class EmsConstraint {

    @TableId(type = IdType.AUTO)
    private Long constraintId;

    private Long tenantId;

    private Long stationId;

    private BigDecimal socMin;

    private BigDecimal socMax;

    private BigDecimal chargePowerMax;

    private BigDecimal dischargePowerMax;

    private BigDecimal tempMax;

    private BigDecimal voltageMax;

    private BigDecimal currentMax;

    /** 扩展安全包络 JSON */
    private String safetyEnvelope;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
