package com.energyx.access.lifecycle;

import com.energyx.access.mapper.DeviceStatusMapper;
import com.energyx.access.mapper.OnlineRecordMapper;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 设备生命周期落地（Phase 4 §6 任务 4）：
 *
 * <ul>
 * <li>刷新 iot_device 在线态（ONLINE→status=3+broker_node；OFFLINE→status=2+累计在线秒数）；</li>
 * <li>按月分区写 iot_device_online_record（审计）；</li>
 * <li>设备上线触发离线指令补发（iot:cmd:q → 重新桥接下行）。</li>
 * </ul>
 *
 * <p>
 * 在线权威状态仍在 Redis iot:online（Broker 心跳续期，30s 过期判离线）， 本服务落 MySQL 的是审计/近线视图，读写频率低（每设备每秒 1
 * 次量级）。
 * </p>
 */
@Slf4j
// 显式 bean 名：默认名 'lifecycleProcessor' 与 Spring 内部 LIFECYCLE_PROCESSOR_BEAN_NAME 冲突，
// 会在 finishRefresh 时报 BeanNotOfRequiredTypeException（P0-4 全栈启动暴露的存量缺陷）
@Component("accessLifecycleProcessor")
public class LifecycleProcessor {

	private final DeviceStatusMapper deviceStatusMapper;

	private final OnlineRecordMapper onlineRecordMapper;

	private final OfflineCommandRedeliverer offlineRedeliverer;

	private final SnowflakeIdGenerator idGenerator;

	public LifecycleProcessor(DeviceStatusMapper deviceStatusMapper, OnlineRecordMapper onlineRecordMapper,
			OfflineCommandRedeliverer offlineRedeliverer, SnowflakeIdGenerator idGenerator) {
		this.deviceStatusMapper = deviceStatusMapper;
		this.onlineRecordMapper = onlineRecordMapper;
		this.offlineRedeliverer = offlineRedeliverer;
		this.idGenerator = idGenerator;
	}

	/**
	 * 生命周期消息分发：按 eventType 路由到上线/下线/封禁/解封处理（deviceId 缺失则忽略）。
	 * @param msg 生命周期消息
	 */
	public void process(LifecycleMessage msg) {
		if (msg.getDeviceId() == null) {
			log.warn("[Access] lifecycle 缺 deviceId，忽略");
			return;
		}
		// 按事件类型路由：上线/下线/封禁/解封分别落地处理
		if ("ONLINE".equals(msg.getEventType())) {
			handleOnline(msg);
		}
		else if ("OFFLINE".equals(msg.getEventType())) {
			handleOffline(msg);
		}
		else if ("BANNED".equals(msg.getEventType())) {
			handleBanned(msg);
		}
		else if ("UNBANNED".equals(msg.getEventType())) {
			handleUnbanned(msg);
		}
		else {
			log.warn("[Access] lifecycle 未知 eventType={} deviceId={}", msg.getEventType(), msg.getDeviceId());
		}
	}

	/**
	 * 上线处理：刷新设备在线态（status=3+broker_node）→ 写上线记录 → 触发离线指令补发。
	 * @param msg 上线生命周期消息
	 */
	private void handleOnline(LifecycleMessage msg) {
		Date now = new Date();
		deviceStatusMapper.updateOnline(msg.getDeviceId(), msg.getBrokerNode(), msg.getIp(), now);
		// 写上线记录（审计），并触发离线期间堆积指令的补发
		insertRecord(msg, 1, now);
		offlineRedeliverer.redeliver(msg.getDeviceId());
		log.info("[Access] 设备上线 deviceId={} node={}", msg.getDeviceId(), msg.getBrokerNode());
	}

	/**
	 * 离线处理：刷新设备离线态（status=2+累计在线秒数）→ 写离线记录。
	 * @param msg 离线生命周期消息
	 */
	private void handleOffline(LifecycleMessage msg) {
		Date now = new Date();
		deviceStatusMapper.updateOffline(msg.getDeviceId(), now);
		insertRecord(msg, 2, now);
		log.info("[Access] 设备离线 deviceId={} reason={}", msg.getDeviceId(), msg.getReason());
	}

	/**
	 * 封禁回写（status=5）：Redis 封禁是权威，DB 的 5 是审计视图，ONLINE 事件不会再覆盖回 3。
	 * @param msg 封禁生命周期消息
	 */
	private void handleBanned(LifecycleMessage msg) {
		deviceStatusMapper.updateBanned(msg.getDeviceId());
		log.info("[Access] 设备封禁 deviceId={} reason={}", msg.getDeviceId(), msg.getReason());
	}

	/**
	 * 解封回写（status=5 → 2）：认证成功补发的 UNBANNED 事件回写设备表。
	 * @param msg 解封生命周期消息
	 */
	private void handleUnbanned(LifecycleMessage msg) {
		deviceStatusMapper.updateUnbanned(msg.getDeviceId());
		log.info("[Access] 设备解封 deviceId={}", msg.getDeviceId());
	}

	/**
	 * 写入上下线记录（按月分区审计），eventType=1 上线 / 2 离线。
	 * @param msg 生命周期消息
	 * @param eventType 事件类型（1=上线，2=离线）
	 * @param now 事件发生时间
	 */
	private void insertRecord(LifecycleMessage msg, int eventType, Date now) {
		onlineRecordMapper.insert(idGenerator.nextId(), msg.getDeviceId(),
				msg.getTenantId() == null ? 0L : msg.getTenantId(), eventType, msg.getReason(), msg.getIp(),
				msg.getBrokerNode(), now);
	}

}
