package com.energyx.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ota.entity.OtaTaskDeviceRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * OTA 任务-设备明细 Mapper（流水表，复合主键；批量写入/状态推进用 BaseMapper 基础方法 + Service 层条件更新）。
 */
@Mapper
public interface OtaTaskDeviceMapper extends BaseMapper<OtaTaskDeviceRow> {

}
