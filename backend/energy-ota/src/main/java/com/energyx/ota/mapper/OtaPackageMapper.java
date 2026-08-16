package com.energyx.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ota.entity.OtaPackageRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * OTA 升级包 Mapper（BaseMapper 提供 CRUD；逻辑删除由 @TableLogic 自动过滤）。
 */
@Mapper
public interface OtaPackageMapper extends BaseMapper<OtaPackageRow> {

}
