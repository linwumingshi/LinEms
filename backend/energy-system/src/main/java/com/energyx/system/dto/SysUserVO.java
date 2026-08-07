package com.energyx.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户展示视图（不携带密码哈希，附带单位名与角色信息）。
 */
@Data
public class SysUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long tenantId;

    private Long enterpriseId;

    /** 单位名称（冗余展示） */
    private String enterpriseName;

    private String username;

    private String realName;

    private String phone;

    private String email;

    /** 状态：0 禁用 1 启用 2 锁定 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;

    /** 已分配角色 ID */
    private List<Long> roleIds;

    /** 已分配角色名称 */
    private List<String> roleNames;
}
