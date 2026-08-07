package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 分时电价（ems_electricity_price）。表无 update_time 列。 */
@Data
@TableName("ems_electricity_price")
public class EmsElectricityPrice {

    @TableId(type = IdType.AUTO)
    private Long priceId;

    private Long tenantId;

    private Long stationId;

    private String region;

    /** DEEP/PEEK/PEAK/FLAT/VALLEY */
    private String priceType;

    private LocalTime startTime;

    private LocalTime endTime;

    private BigDecimal price;

    private LocalDate validFrom;

    private LocalDate validTo;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
