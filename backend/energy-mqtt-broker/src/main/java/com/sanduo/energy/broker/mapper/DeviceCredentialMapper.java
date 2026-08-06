package com.sanduo.energy.broker.mapper;

import com.sanduo.energy.broker.auth.CredentialRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * iot_device_credential 只读投影（凭据密钥/状态/过期时间）。
 */
public interface DeviceCredentialMapper {

    @Select("SELECT device_id, device_secret, auth_status, expire_time " +
            "FROM iot_device_credential WHERE device_id = #{deviceId} LIMIT 1")
    CredentialRow selectByDeviceId(@Param("deviceId") Long deviceId);
}
