package com.sanduo.energy.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审计字段基类：create_time / update_time / deleted。
 * 由 {@link com.sanduo.energy.common.config.AuditMetaObjectHandler} 自动填充。
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0 正常 1 删除 */
    @TableLogic
    @TableField(value = "deleted")
    private Integer deleted;
}
