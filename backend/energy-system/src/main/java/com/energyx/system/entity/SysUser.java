package com.energyx.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.energyx.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统用户（登录账号）。
 * 对应表 sys_user；password 存 Spring Security DelegatingPasswordEncoder 格式
 * （{bcrypt}$2a$... 或 V1 种子的 {noop}admin123）。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long userId;

    private Long tenantId;

    private Long enterpriseId;

    private String username;

    /** 密码哈希，绝不随 JSON 序列化输出 */
    @JsonIgnore
    private String password;

    private String realName;

    private String phone;

    private String email;

    private String avatar;

    /** 状态：0 禁用 1 启用 2 锁定 */
    private Integer status;

    private LocalDateTime lastLoginTime;
}
