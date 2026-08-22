package com.energyx.broker.mapper;

import com.energyx.broker.auth.CredentialRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * iot_device_credential 只读投影（凭据密钥/状态/过期时间）。
 */
public interface DeviceCredentialMapper {

	/**
	 * 按设备 ID 查询凭据只读投影（密钥/状态/过期时间）。
	 * @param deviceId 设备 ID
	 * @return 凭据行；查不到返回 null
	 */
	@Select("""
			SELECT device_id, device_secret, auth_status, expire_time
			FROM iot_device_credential WHERE device_id = #{deviceId} LIMIT 1
			""")
	CredentialRow selectByDeviceId(@Param("deviceId") Long deviceId);

}
