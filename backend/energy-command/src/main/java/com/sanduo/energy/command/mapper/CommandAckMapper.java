package com.sanduo.energy.command.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * iot_command_ack 留存（原始 ACK 报文，排查用，按月分区）。
 */
@Mapper
public interface CommandAckMapper {

    @Insert("""
            INSERT INTO iot_command_ack (ack_id, command_id, device_id, ack_payload)
            VALUES (#{ackId}, #{commandId}, #{deviceId}, #{ackPayload})
            """)
    int insertAck(@Param("ackId") long ackId, @Param("commandId") String commandId,
                  @Param("deviceId") long deviceId, @Param("ackPayload") String ackPayload);
}
