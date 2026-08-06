package com.sanduo.energy.shadow.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * iot_shadow_history（变更历史，仅关键变更、节流写入，按月分区）。
 */
@Mapper
public interface ShadowHistoryMapper {

    @Insert("""
            INSERT INTO iot_shadow_history (device_id, version, snapshot, operator_type)
            VALUES (#{deviceId}, #{version}, #{snapshot}, #{operatorType})
            """)
    int insert(@Param("deviceId") long deviceId, @Param("version") int version,
               @Param("snapshot") String snapshot, @Param("operatorType") int operatorType);
}
