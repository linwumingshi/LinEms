package com.energyx.ems.mapper;

import com.energyx.ems.model.MeterDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 站内进线电能表解析（跨库读 es_device.iot_device）。 */
@Mapper
public interface MeterDeviceMapper {

	@Select("""
			SELECT device_id, tenant_id, product_key, device_name, status
			FROM es_device.iot_device
			WHERE tenant_id = #{tenantId} AND station_id = #{stationId}
			  AND device_type = 'METER' AND product_key = #{productKey}
			  AND deleted = 0 AND status IN (2, 3)
			ORDER BY device_id
			""")
	List<MeterDevice> selectByStation(@Param("tenantId") Long tenantId, @Param("stationId") Long stationId,
			@Param("productKey") String productKey);

}
