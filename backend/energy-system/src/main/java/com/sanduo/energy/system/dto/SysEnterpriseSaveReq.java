package com.sanduo.energy.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 单位创建/更新请求。
 */
@Data
public class SysEnterpriseSaveReq {

    @NotBlank(message = "单位编码不能为空")
    @Size(max = 64, message = "单位编码长度不能超过 64")
    private String enterpriseCode;

    @NotBlank(message = "单位名称不能为空")
    @Size(max = 128, message = "单位名称长度不能超过 128")
    private String enterpriseName;

    /** 父单位 ID（0 或空 = 顶级） */
    private Long parentId;

    private Integer sort;

    /** 默认启用 */
    private Integer status;
}
