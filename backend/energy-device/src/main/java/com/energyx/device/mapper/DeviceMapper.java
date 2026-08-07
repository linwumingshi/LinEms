package com.energyx.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备主表 Mapper（统一设备树）。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
