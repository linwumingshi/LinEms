package com.energyx.access.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 设备在线态更新（状态机：3在线 / 2已激活离线）。 对应 iot_device 表注释：0未注册 1未激活 2已激活(离线) 3在线 4禁用 5封禁。
 */
@Mapper
public interface DeviceStatusMapper {

	/**
	 * 标记设备上线：status→3，写入 broker_node/ip/last_online_time。 仅 2 已激活(离线)/3 在线 可更新，避免乱序
	 * ONLINE 覆盖管理态（4禁用/5封禁）。
	 * @param deviceId 设备 ID
	 * @param brokerNode 接入 Broker 节点（下行路由定位）
	 * @param ip 设备上线 IP
	 * @param time 上线时间戳
	 * @return 影响行数（0 表示状态态不匹配，未更新）
	 */
	@Update("""
			UPDATE iot_device
			SET status = 3, broker_node = #{brokerNode}, ip = #{ip}, last_online_time = #{time}
			WHERE device_id = #{deviceId} AND status IN (2,3)
			""")
	int updateOnline(@Param("deviceId") long deviceId, @Param("brokerNode") String brokerNode, @Param("ip") String ip,
			@Param("time") Date time);

	/**
	 * 累计在线秒数由 last_online_time → 本次离线时间戳差值得出（离线时无并发竞争，直接累计）。 状态保护：仅 2 已激活(离线)/3 在线
	 * 可回写离线态。禁用(4)/封禁(5) 属于管理态， 乱序 OFFLINE 事件（禁用在线设备踢线、封禁后延迟下线）不得覆盖，防止禁用/封禁被静默撤销。
	 */
	@Update("""
			UPDATE iot_device
			SET status = 2, broker_node = NULL, last_offline_time = #{time},
			    online_seconds = online_seconds +
			        COALESCE(TIMESTAMPDIFF(SECOND, last_online_time, #{time}), 0)
			WHERE device_id = #{deviceId} AND status IN (2,3)
			""")
	int updateOffline(@Param("deviceId") long deviceId, @Param("time") Date time);

	/** 封禁回写：仅 2 已激活(离线)/3 在线 可进入 5 封禁（禁用/未激活态不动） */
	@Update("UPDATE iot_device SET status = 5 WHERE device_id = #{deviceId} AND status IN (2,3)")
	int updateBanned(@Param("deviceId") long deviceId);

	/** 解封回写：仅 5 封禁 可回到 2 已激活(离线) */
	@Update("UPDATE iot_device SET status = 2 WHERE device_id = #{deviceId} AND status = 5")
	int updateUnbanned(@Param("deviceId") long deviceId);

}
