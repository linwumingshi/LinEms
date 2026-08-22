package com.energyx.access.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 设备上下线记录写入（按月分区 iot_device_online_record）。
 */
@Mapper
public interface OnlineRecordMapper {

	/**
	 * 写入一条设备上下线记录到按月分区表 iot_device_online_record。
	 * @param recordId 记录 ID
	 * @param deviceId 设备 ID
	 * @param tenantId 租户 ID
	 * @param eventType 事件类型（1上线 / 2离线 等）
	 * @param reason 事件原因
	 * @param ip 设备 IP
	 * @param brokerNode 接入 Broker 节点
	 * @param reportTime 上报时间
	 * @return 影响行数（1 表示成功插入）
	 */
	@Insert("""
			INSERT INTO iot_device_online_record
			  (record_id, device_id, tenant_id, event_type, reason, ip, broker_node, report_time)
			VALUES
			  (#{recordId}, #{deviceId}, #{tenantId}, #{eventType}, #{reason}, #{ip}, #{brokerNode}, #{reportTime})
			""")
	int insert(@Param("recordId") long recordId, @Param("deviceId") long deviceId, @Param("tenantId") long tenantId,
			@Param("eventType") int eventType, @Param("reason") String reason, @Param("ip") String ip,
			@Param("brokerNode") String brokerNode, @Param("reportTime") Date reportTime);

}
