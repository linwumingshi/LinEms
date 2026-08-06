package com.sanduo.energy.command.mapper;

import com.sanduo.energy.command.model.CommandRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * iot_command 数据访问（state 条件更新天然幂等：重复 ACK/重放因 WHERE state 不满足而空操作）。
 */
@Mapper
public interface CommandMapper {

    @Select("""
            SELECT command_id, tenant_id, device_id, product_key, command_name, command_type,
                   params, state, retry_count, max_retry, timeout_ms,
                   sent_time, received_time, executing_time, finish_time,
                   result, error_code, error_msg, create_by, create_time
            FROM iot_command WHERE command_id = #{commandId}
            """)
    CommandRow selectById(@Param("commandId") String commandId);

    /** 创建指令，初始 state=0（CREATED） */
    @Insert("""
            INSERT INTO iot_command (command_id, tenant_id, device_id, product_key, command_name,
                                     command_type, params, state, max_retry, timeout_ms, create_by)
            VALUES (#{commandId}, #{tenantId}, #{deviceId}, #{productKey}, #{commandName},
                    #{commandType}, #{params}, 0, #{maxRetry}, #{timeoutMs}, #{createBy})
            """)
    int insert(@Param("commandId") String commandId, @Param("tenantId") long tenantId,
               @Param("deviceId") long deviceId, @Param("productKey") String productKey,
               @Param("commandName") String commandName, @Param("commandType") int commandType,
               @Param("params") String params, @Param("maxRetry") int maxRetry,
               @Param("timeoutMs") int timeoutMs, @Param("createBy") long createBy);

    /** 下发（在线直发 / 离线队列补发）：CREATED → SENT */
    @Update("""
            UPDATE iot_command SET state = 1, sent_time = #{now}
            WHERE command_id = #{commandId} AND state = 0
            """)
    int updateSent(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** ACK DEVICE_RECEIVED：SENT → DEVICE_RECEIVED */
    @Update("""
            UPDATE iot_command SET state = 2, received_time = #{now}
            WHERE command_id = #{commandId} AND state = 1
            """)
    int updateReceived(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** ACK EXECUTING：SENT / DEVICE_RECEIVED → EXECUTING */
    @Update("""
            UPDATE iot_command SET state = 3, executing_time = #{now}
            WHERE command_id = #{commandId} AND state IN (1, 2)
            """)
    int updateExecuting(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** ACK SUCCESS：任意在途状态 → SUCCESS（终态） */
    @Update("""
            UPDATE iot_command SET state = 4, result = #{result}, finish_time = #{now}
            WHERE command_id = #{commandId} AND state IN (1, 2, 3)
            """)
    int updateSuccess(@Param("commandId") String commandId, @Param("result") String result,
                      @Param("now") LocalDateTime now);

    /** ACK FAILED：任意在途状态 → FAILED（终态） */
    @Update("""
            UPDATE iot_command SET state = 5, error_code = #{errorCode}, finish_time = #{now}
            WHERE command_id = #{commandId} AND state IN (1, 2, 3)
            """)
    int updateFailed(@Param("commandId") String commandId, @Param("errorCode") String errorCode,
                     @Param("now") LocalDateTime now);

    /** 超时重试（设备在线）：在途状态 → SENT，重试计数 +1，重掷超时锚点 */
    @Update("""
            UPDATE iot_command SET state = 1, retry_count = retry_count + 1,
                   sent_time = #{now}, error_code = NULL, error_msg = NULL
            WHERE command_id = #{commandId} AND state IN (1, 2, 3)
            """)
    int resendOnline(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** 超时重试（设备离线）：在途状态 → CREATED 重新入队，sent_time 清空免被再扫 */
    @Update("""
            UPDATE iot_command SET state = 0, retry_count = retry_count + 1,
                   sent_time = NULL, error_code = NULL, error_msg = NULL
            WHERE command_id = #{commandId} AND state IN (1, 2, 3)
            """)
    int requeue(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** 重试耗尽：在途状态 → TIMEOUT（终态） */
    @Update("""
            UPDATE iot_command SET state = 6, error_msg = 'ACK_TIMEOUT', finish_time = #{now}
            WHERE command_id = #{commandId} AND state IN (1, 2, 3)
            """)
    int markTerminalTimeout(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    /** 超时扫描候选：在途状态且 sent_time 早于截止时刻（idx_cmd_state_time） */
    @Select("""
            SELECT command_id, device_id, product_key, command_name, command_type, params,
                   state, retry_count, max_retry, timeout_ms
            FROM iot_command
            WHERE state IN (1, 2, 3) AND sent_time IS NOT NULL AND sent_time < #{cutoff}
            ORDER BY sent_time ASC
            LIMIT #{limit}
            """)
    List<CommandRow> selectTimeoutCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 同设备在途同名单指令（用于 shadow delta 合并去重） */
    @Select("""
            SELECT command_id FROM iot_command
            WHERE device_id = #{deviceId} AND command_name = #{commandName} AND state IN (1, 2, 3)
            LIMIT 1
            """)
    String selectInFlightByDeviceAndName(@Param("deviceId") long deviceId, @Param("commandName") String commandName);
}
