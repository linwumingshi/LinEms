package com.energyx.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.config.CommandProperties;
import com.energyx.command.mapper.CommandAckMapper;
import com.energyx.command.mapper.CommandMapper;
import com.energyx.command.client.DeviceFeignClient;
import com.energyx.command.model.CommandRow;
import com.energyx.command.model.DeviceInfo;
import com.energyx.command.mqtt.CommandKafkaProducer;
import com.energyx.command.web.dto.CommandView;
import com.energyx.command.web.dto.CreateCommandRequest;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.enums.CommandState;
import com.energyx.common.message.CommandAckMessage;
import com.energyx.common.message.CommandDownMessage;
import com.energyx.common.message.ShadowDeltaMessage;
import com.energyx.common.model.Result;
import com.energyx.common.redis.IdempotencyUtils;
import com.energyx.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandService 核心路径测试（Mock Mapper/Redis/Kafka/幂等，ObjectMapper/雪花/配置用真实实现）。
 *
 * <p>
 * 覆盖：在线直发、离线入队、幂等命中、失败释放幂等、ACK 状态机（成功/终态忽略/未知指令）、 delta
 * 物化（在途合并/离线入队）、离线队列补发、超时扫描（重试/耗尽终态）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CommandServiceTest {

	@Mock
	CommandMapper commandMapper;

	@Mock
	CommandAckMapper ackMapper;

	@Mock
	DeviceFeignClient deviceFeignClient;

	@Mock
	StringRedisTemplate redis;

	@Mock
	CommandKafkaProducer producer;

	@Mock
	IdempotencyUtils idempotencyUtils;

	@Mock
	ListOperations<String, String> listOps;

	@Mock
	HashOperations<String, Object, Object> hashOps;

	CommandService service;

	CommandProperties props;

	ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		props = new CommandProperties();
		service = new CommandService(commandMapper, ackMapper, deviceFeignClient, redis, producer, objectMapper,
				idempotencyUtils, props, new SnowflakeIdGenerator());
		lenient().when(redis.opsForList()).thenReturn(listOps);
		lenient().when(redis.opsForHash()).thenReturn(hashOps);
	}

	private static DeviceInfo dev() {
		return new DeviceInfo(100L, 1L, "pk-1", "dn-1", 1);
	}

	private static CommandRow row(String commandId, int state) {
		CommandRow r = new CommandRow();
		r.setCommandId(commandId);
		r.setTenantId(1L);
		r.setDeviceId(100L);
		r.setProductKey("pk-1");
		r.setCommandName("setPower");
		r.setCommandType(2);
		r.setParams("{\"power\":50}");
		r.setState(CommandState.fromCode(state));
		r.setRetryCount(0);
		r.setMaxRetry(3);
		r.setTimeoutMs(15000);
		return r;
	}

	private static CreateCommandRequest req() {
		CreateCommandRequest r = new CreateCommandRequest();
		r.setCommandId("c-1");
		r.setProductKey("pk-1");
		r.setDeviceName("dn-1");
		r.setCommand("setPower");
		r.setParams(Map.of("power", 50));
		return r;
	}

	// ---------------------------------------------------------- create

	@Test
	@DisplayName("设备在线 → 直发 Kafka + 置 SENT")
	void createCommand_onlineDispatchesDirect() {
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1")).thenReturn(Result.ok(dev()));
		when(commandMapper.insert(any(CommandRow.class))).thenReturn(1);
		when(commandMapper.selectById("c-1")).thenReturn(row("c-1", 1));
		when(redis.hasKey("iot:online:100")).thenReturn(true);

		CommandView view = service.createCommand(req());

		assertEquals("c-1", view.getCommandId());
		assertEquals("SENT", view.getStateName());
		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
		verify(commandMapper).updateSent(eq("c-1"), any(LocalDateTime.class));
		verify(idempotencyUtils, never()).release(anyString());
	}

	@Test
	@DisplayName("设备离线 → 写入离线队列，保持 CREATED")
	void createCommand_offlineQueues() {
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1")).thenReturn(Result.ok(dev()));
		when(commandMapper.insert(any(CommandRow.class))).thenReturn(1);
		when(commandMapper.selectById("c-1")).thenReturn(row("c-1", 0));
		when(redis.hasKey("iot:online:100")).thenReturn(false);
		when(listOps.rightPush(eq("iot:cmd:q:100"), anyString())).thenReturn(1L);

		CommandView view = service.createCommand(req());

		assertEquals("CREATED", view.getStateName());
		verify(listOps).rightPush(eq("iot:cmd:q:100"), contains("setPower"));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(commandMapper, never()).updateSent(anyString(), any());
	}

	@Test
	@DisplayName("重复 commandId → 幂等返回既有指令")
	void createCommand_duplicateReturnsExisting() {
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(false);
		when(commandMapper.selectById("c-1")).thenReturn(row("c-1", 4));

		CommandView view = service.createCommand(req());

		assertEquals("c-1", view.getCommandId());
		verify(commandMapper, never()).insert(any(CommandRow.class));
	}

	@Test
	@DisplayName("设备解析失败 → 抛错并释放幂等许可")
	void createCommand_releasesIdempotencyOnFailure() {
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1")).thenReturn(Result.ok(null));

		assertThrows(IllegalArgumentException.class, () -> service.createCommand(req()));
		verify(idempotencyUtils).release("c-1");
	}

	// ---------------------------------------------------------- ack

	@Test
	@DisplayName("ACK SUCCESS → 终态 + 在途清除 + ACK 留存")
	void applyAck_successTransition() {
		CommandAckMessage ack = new CommandAckMessage();
		ack.setCommandId("c-1");
		ack.setDeviceId(100L);
		ack.setStatus("SUCCESS");
		ack.setResult(Map.of("power", 50));
		when(commandMapper.selectById("c-1")).thenReturn(row("c-1", 1));
		when(commandMapper.updateSuccess(eq("c-1"), anyString(), any(LocalDateTime.class))).thenReturn(1);

		service.applyAck(ack);

		verify(commandMapper).updateSuccess(eq("c-1"), contains("power"), any(LocalDateTime.class));
		verify(ackMapper).insertAck(anyLong(), eq("c-1"), eq(100L), anyString());
		verify(hashOps).delete("iot:cmd:inflight:100", "c-1");
	}

	@Test
	@DisplayName("已终态收到 ACK → 幂等忽略")
	void applyAck_terminalIgnored() {
		CommandAckMessage ack = new CommandAckMessage();
		ack.setCommandId("c-1");
		ack.setStatus("SUCCESS");
		when(commandMapper.selectById("c-1")).thenReturn(row("c-1", 4));

		service.applyAck(ack);

		verify(commandMapper, never()).updateSuccess(anyString(), anyString(), any(LocalDateTime.class));
		verify(ackMapper, never()).insertAck(anyLong(), anyString(), anyLong(), anyString());
	}

	@Test
	@DisplayName("ACK 对应指令不存在 → 丢弃")
	void applyAck_unknownCommand() {
		CommandAckMessage ack = new CommandAckMessage();
		ack.setCommandId("ghost");
		ack.setStatus("EXECUTING");
		when(commandMapper.selectById("ghost")).thenReturn(null);

		service.applyAck(ack);

		verify(commandMapper, never()).updateExecuting(anyString(), any(LocalDateTime.class));
	}

	// ---------------------------------------------------------- delta

	@Test
	@DisplayName("存在在途 setProperties → 合并跳过")
	void materializeDelta_inflightCoalesce() {
		ShadowDeltaMessage d = new ShadowDeltaMessage();
		d.setDeviceId(100L);
		d.setTenantId(1L);
		d.setDesired(Map.of("power", 100));
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(dev()));
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn("existing");

		service.materializeDelta(d);

		verify(commandMapper, never()).insert(any(CommandRow.class));
	}

	@Test
	@DisplayName("无在途且离线 → 物化 setProperties 并入队")
	void materializeDelta_offlineQueues() {
		ShadowDeltaMessage d = new ShadowDeltaMessage();
		d.setDeviceId(100L);
		d.setTenantId(1L);
		d.setDesired(Map.of("power", 100));
		d.setVersion(2);
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(dev()));
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);
		when(commandMapper.insert(any(CommandRow.class))).thenReturn(1);
		CommandRow materialized = row("c-new", 0);
		materialized.setCommandName("setProperties"); // dispatch 重新读取的落库行是 setProperties
		when(commandMapper.selectById(anyString())).thenReturn(materialized);
		when(redis.hasKey("iot:online:100")).thenReturn(false);
		when(listOps.rightPush(eq("iot:cmd:q:100"), anyString())).thenReturn(1L);

		service.materializeDelta(d);

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(eq("iot:cmd:q:100"), contains("setProperties"));
	}

	// ---------------------------------------------------------- offline queue

	@Test
	@DisplayName("上线补发：置 SENT 后下发，非 CREATED 跳过")
	void drainOfflineQueue_sendsAndMarks() throws Exception {
		CommandDownMessage m = new CommandDownMessage();
		m.setCommandId("c-9");
		m.setDeviceId(100L);
		m.setProductKey("pk-1");
		m.setDeviceName("dn-1");
		m.setCommand("setPower");
		String json = objectMapper.writeValueAsString(m);
		when(listOps.leftPop("iot:cmd:q:100")).thenReturn(json).thenReturn(null);
		when(commandMapper.updateSent(eq("c-9"), any(LocalDateTime.class))).thenReturn(1);

		service.drainOfflineQueue(100L);

		verify(commandMapper).updateSent(eq("c-9"), any(LocalDateTime.class));
		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), eq(json));
	}

	@Test
	@DisplayName("补发时指令已非 CREATED → 跳过不重发")
	void drainOfflineQueue_skipsNonCreated() throws Exception {
		CommandDownMessage m = new CommandDownMessage();
		m.setCommandId("c-9");
		m.setDeviceId(100L);
		String json = objectMapper.writeValueAsString(m);
		when(listOps.leftPop("iot:cmd:q:100")).thenReturn(json).thenReturn(null);
		when(commandMapper.updateSent(eq("c-9"), any(LocalDateTime.class))).thenReturn(0);

		service.drainOfflineQueue(100L);

		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	// ---------------------------------------------------------- timeout scan

	@Test
	@DisplayName("超时重试：在线重发并计数 +1")
	void timeoutScan_retriesOnline() {
		CommandRow row = row("c-1", 1);
		row.setRetryCount(1);
		when(commandMapper.selectTimeoutCandidates(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
		when(redis.hasKey("iot:online:100")).thenReturn(true);
		when(commandMapper.resendOnline(eq("c-1"), any(LocalDateTime.class))).thenReturn(1);
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(dev()));

		service.timeoutScan();

		verify(commandMapper).resendOnline(eq("c-1"), any(LocalDateTime.class));
		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("超时重试耗尽 → 置 TIMEOUT 终态")
	void timeoutScan_exhaustedTerminal() {
		CommandRow row = row("c-1", 3);
		row.setRetryCount(3);
		when(commandMapper.selectTimeoutCandidates(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
		when(commandMapper.markTerminalTimeout(eq("c-1"), any(LocalDateTime.class))).thenReturn(1);

		service.timeoutScan();

		verify(commandMapper).markTerminalTimeout(eq("c-1"), any(LocalDateTime.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(hashOps).delete("iot:cmd:inflight:100", "c-1");
	}

}
