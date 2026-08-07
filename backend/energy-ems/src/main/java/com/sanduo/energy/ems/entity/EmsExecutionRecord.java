package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 策略执行记录（ems_execution_record）。execute_time 由 DB DEFAULT CURRENT_TIMESTAMP 填充。 */
@Data
@TableName("ems_execution_record")
public class EmsExecutionRecord {

    @TableId(type = IdType.AUTO)
    private Long execId;

    private Long tenantId;

    private Long planId;

    private String commandId;

    private Long deviceId;

    /** CHARGE/DISCHARGE/STANDBY */
    private String action;

    /** 下发参数 JSON */
    private String params;

    /** 执行回执 JSON */
    private String result;

    private LocalDateTime executeTime;
}
