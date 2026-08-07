package com.energyx.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户创建/更新请求。
 * <p>password 仅在创建时必填；更新时留空表示不修改密码。</p>
 */
@Data
public class SysUserSaveReq {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "用户名仅允许字母、数字、下划线、点、短横线")
    private String username;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String realName;

    @Size(max = 32, message = "手机号长度不能超过 32")
    private String phone;

    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String email;

    private Long enterpriseId;

    /** 状态：0 禁用 1 启用 2 锁定 */
    private Integer status;

    /** 创建时必填，更新时留空不改 */
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 位")
    private String password;

    /** 分配的角色 ID 集合（null 表示不修改） */
    private List<Long> roleIds;
}
