package com.energyx.access.mapper;

import com.energyx.access.device.DeviceInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备信息只读投影（deviceKey={product_key}_{device_name} → 设备维度上下文）。
 * 缓存路径：cache:device:{deviceKey}（Redis）→ 本查询兜底。
 */
@Mapper
public interface DeviceMapper {

    @Select("""
            SELECT device_id, tenant_id, enterprise_id, station_id, device_type, status
            FROM iot_device
            WHERE product_key = #{productKey} AND device_name = #{deviceName} AND deleted = 0
            LIMIT 1
            """)
    DeviceInfo findByProductAndName(@Param("productKey") String productKey,
                                    @Param("deviceName") String deviceName);
}
