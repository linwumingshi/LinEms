package com.energyx.broker.mapper;

import com.energyx.broker.auth.DeviceRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * iot_device 只读投影（认证凭据兜底查询）。 注：分片场景（ShardingSphere 按 device_id hash）SQL 不变，物理表由中间件改写。
 */
public interface DeviceMapper {

	/**
	 * 按 productKey + deviceName 查询设备只读投影（认证凭据兜底查询，已排除 deleted）。
	 * @param productKey 产品 Key（可含 '_'；deviceName 按最后 '_' 拆分）
	 * @param deviceName 设备名
	 * @return 设备行；查不到返回 null
	 */
	@Select("""
			SELECT device_id, tenant_id, product_key, device_name, status
			FROM iot_device WHERE product_key = #{pk} AND device_name = #{dn} AND deleted = 0 LIMIT 1
			""")
	DeviceRow selectByProductKeyAndName(@Param("pk") String productKey, @Param("dn") String deviceName);

}
