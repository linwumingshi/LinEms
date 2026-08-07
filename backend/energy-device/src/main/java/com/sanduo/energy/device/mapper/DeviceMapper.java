package com.sanduo.energy.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanduo.energy.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备主表 Mapper（统一设备树）。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
