package com.sanduo.energy.access.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 设备上下线记录写入（按月分区 iot_device_online_record）。
 */
@Mapper
public interface OnlineRecordMapper {

    @Insert("""
            INSERT INTO iot_device_online_record
              (record_id, device_id, tenant_id, event_type, reason, ip, broker_node, report_time)
            VALUES
              (#{recordId}, #{deviceId}, #{tenantId}, #{eventType}, #{reason}, #{ip}, #{brokerNode}, #{reportTime})
            """)
    int insert(@Param("recordId") long recordId,
               @Param("deviceId") long deviceId,
               @Param("tenantId") long tenantId,
               @Param("eventType") int eventType,
               @Param("reason") String reason,
               @Param("ip") String ip,
               @Param("brokerNode") String brokerNode,
               @Param("reportTime") Date reportTime);
}
