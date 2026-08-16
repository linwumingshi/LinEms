package com.energyx.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ota.entity.OtaTaskRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * OTA 批次任务 Mapper（BaseMapper 提供 CRUD；逻辑删除由 @TableLogic 自动过滤）。
 */
@Mapper
public interface OtaTaskMapper extends BaseMapper<OtaTaskRow> {

}
