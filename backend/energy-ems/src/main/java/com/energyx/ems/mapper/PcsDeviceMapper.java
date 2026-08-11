package com.energyx.ems.mapper;

import com.energyx.ems.model.PcsDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PCS 下发设备查询（跨 schema 读 es_device.iot_device，单 DataSource 全限定名，复用 energy-command 的
 * DeviceInfoMapper 模式）。 按 (tenantId, stationId) + productKey + device_type=PCS 解析计划下发目标，
 * 支持一电站多 PCS；软删（deleted=1）与禁用/封禁（status 0/1/4/5）设备不可下发（P0-2）。
 */
@Mapper
public interface PcsDeviceMapper {

	@Select("""
			SELECT device_id, tenant_id, product_key, device_name, status
			FROM es_device.iot_device
			WHERE tenant_id = #{tenantId} AND station_id = #{stationId}
			  AND device_type = 'PCS' AND product_key = #{productKey}
			  AND deleted = 0 AND status IN (2, 3)
			ORDER BY device_id
			""")
	List<PcsDevice> selectByStation(@Param("tenantId") Long tenantId, @Param("stationId") Long stationId,
			@Param("productKey") String productKey);

}
