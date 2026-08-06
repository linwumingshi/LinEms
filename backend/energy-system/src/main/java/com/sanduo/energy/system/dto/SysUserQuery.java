package com.sanduo.energy.system.dto;

import lombok.Data;

/**
 * 用户分页查询条件。
 */
@Data
public class SysUserQuery {

    private long current = 1;

    private long size = 10;

    /** 用户名/姓名模糊搜索 */
    private String keyword;

    /** 状态过滤：0 禁用 1 启用 2 锁定 */
    private Integer status;

    /** 单位过滤 */
    private Long enterpriseId;
}
