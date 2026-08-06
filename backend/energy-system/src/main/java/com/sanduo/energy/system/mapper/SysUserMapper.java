package com.sanduo.energy.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanduo.energy.system.entity.SysUser;

/**
 * 用户 Mapper。逻辑删除由 @TableLogic 自动过滤 deleted=0。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
