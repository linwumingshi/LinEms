package com.sanduo.energy.system.dto;

import lombok.Data;

/**
 * 角色分页查询条件。
 */
@Data
public class SysRoleQuery {

    private long current = 1;

    private long size = 10;

    /** 角色编码/名称模糊搜索 */
    private String keyword;

    /** 状态过滤：0 禁用 1 启用 */
    private Integer status;
}
