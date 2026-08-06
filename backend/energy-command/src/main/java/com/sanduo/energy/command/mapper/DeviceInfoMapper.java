package com.sanduo.energy.command.mapper;

import com.sanduo.energy.command.model.DeviceInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备信息查询（跨 schema 读 es_device.iot_device，单 DataSource 全限定名）。
 *
 * <p>指令域只需设备身份（pk/dn/租户），不建冗余副本；软删设备不可下发。</p>
 */
@Mapper
public interface DeviceInfoMapper {

    @Select("""
            SELECT device_id, tenant_id, product_key, device_name, status
            FROM es_device.iot_device
            WHERE device_id = #{deviceId} AND deleted = 0
            """)
    DeviceInfo selectByDeviceId(@Param("deviceId") long deviceId);

    @Select("""
            SELECT device_id, tenant_id, product_key, device_name, status
            FROM es_device.iot_device
            WHERE product_key = #{productKey} AND device_name = #{deviceName} AND deleted = 0
            """)
    DeviceInfo selectByProductAndName(@Param("productKey") String productKey,
                                      @Param("deviceName") String deviceName);
}
