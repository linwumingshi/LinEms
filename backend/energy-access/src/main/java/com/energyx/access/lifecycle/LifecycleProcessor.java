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

	public void process(LifecycleMessage msg) {
		if (msg.getDeviceId() == null) {
			log.warn("[Access] lifecycle 缺 deviceId，忽略");
			return;
		}
		if ("ONLINE".equals(msg.getEventType())) {
			handleOnline(msg);
		}
		else if ("OFFLINE".equals(msg.getEventType())) {
			handleOffline(msg);
		}
		else {
			log.warn("[Access] lifecycle 未知 eventType={} deviceId={}", msg.getEventType(), msg.getDeviceId());
		}
	}

	private void handleOnline(LifecycleMessage msg) {
		Date now = new Date();
		deviceStatusMapper.updateOnline(msg.getDeviceId(), msg.getBrokerNode(), msg.getIp(), now);
		insertRecord(msg, 1, now);
		offlineRedeliverer.redeliver(msg.getDeviceId());
		log.info("[Access] 设备上线 deviceId={} node={}", msg.getDeviceId(), msg.getBrokerNode());
	}

	private void handleOffline(LifecycleMessage msg) {
		Date now = new Date();
		deviceStatusMapper.updateOffline(msg.getDeviceId(), now);
		insertRecord(msg, 2, now);
		log.info("[Access] 设备离线 deviceId={} reason={}", msg.getDeviceId(), msg.getReason());
	}

	private void insertRecord(LifecycleMessage msg, int eventType, Date now) {
		onlineRecordMapper.insert(idGenerator.nextId(), msg.getDeviceId(),
				msg.getTenantId() == null ? 0L : msg.getTenantId(), eventType, msg.getReason(), msg.getIp(),
				msg.getBrokerNode(), now);
	}

}
