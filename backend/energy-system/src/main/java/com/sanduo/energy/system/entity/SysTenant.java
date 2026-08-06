package com.sanduo.energy.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sanduo.energy.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户（=集团）。
 * 对应表 sys_tenant；quota 为 JSON 资源配额（以 String 承载 JSON 文本）。
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long tenantId;

    private String tenantCode;

    private String tenantName;

    private String contact;

    private String phone;

    /** JSON 资源配额，如 {"deviceLimit":100000,"ingestRate":500} */
    private String quota;

    /** 状态：0 禁用 1 启用 */
    private Integer status;
}
