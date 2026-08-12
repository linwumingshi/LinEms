package com.energyx.access.lifecycle;

import com.energyx.access.mapper.DeviceStatusMapper;
import com.energyx.access.mapper.OnlineRecordMapper;
import com.energyx.common.message.LifecycleMessage;
import com.energyx.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 生命周期处理测试：BANNED/UNBANNED 回写封禁态、ONLINE 仍走在线态、未知事件忽略。
 */
class LifecycleProcessorTest {

	private DeviceStatusMapper statusMapper;

	private OnlineRecordMapper recordMapper;

	private OfflineCommandRedeliverer redeliverer;

	private SnowflakeIdGenerator idGenerator;

	private LifecycleProcessor processor;

	@BeforeEach
	void setUp() {
		statusMapper = mock(DeviceStatusMapper.class);
		recordMapper = mock(OnlineRecordMapper.class);
		redeliverer = mock(OfflineCommandRedeliverer.class);
		idGenerator = mock(SnowflakeIdGenerator.class);
		processor = new LifecycleProcessor(statusMapper, recordMapper, redeliverer, idGenerator);
	}

	@Test
	void process_bannedEvent_callsUpdateBanned() {
		LifecycleMessage msg = message("BANNED", 100L);

		processor.process(msg);

		verify(statusMapper).updateBanned(100L);
		verify(statusMapper, never()).updateUnbanned(anyLong());
	}

	@Test
	void process_unbannedEvent_callsUpdateUnbanned() {
		LifecycleMessage msg = message("UNBANNED", 100L);

		processor.process(msg);

		verify(statusMapper).updateUnbanned(100L);
		verify(statusMapper, never()).updateBanned(anyLong());
	}

	@Test
	void process_unknownEvent_ignored() {
		LifecycleMessage msg = message("UNKNOWN", 100L);

		processor.process(msg);

		verify(statusMapper, never()).updateBanned(anyLong());
		verify(statusMapper, never()).updateUnbanned(anyLong());
		verify(statusMapper, never()).updateOnline(anyLong(), any(), any(), any());
		verify(statusMapper, never()).updateOffline(anyLong(), any());
		verify(recordMapper, never()).insert(anyLong(), anyLong(), anyLong(), anyInt(), any(), any(), any(), any());
	}

	@Test
	void process_onlineEvent_stillCallsUpdateOnline() {
		LifecycleMessage msg = message("ONLINE", 100L);
		msg.setBrokerNode("broker-1");
		msg.setIp("1.2.3.4");

		processor.process(msg);

		verify(statusMapper).updateOnline(eq(100L), eq("broker-1"), eq("1.2.3.4"), any());
	}

	private LifecycleMessage message(String eventType, Long deviceId) {
		LifecycleMessage msg = new LifecycleMessage();
		msg.setEventType(eventType);
		msg.setDeviceId(deviceId);
		msg.setTenantId(9L);
		return msg;
	}

}
