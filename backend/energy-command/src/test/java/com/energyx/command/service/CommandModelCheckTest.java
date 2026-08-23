package com.energyx.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.client.DeviceFeignClient;
import com.energyx.command.config.CommandProperties;
import com.energyx.command.mapper.CommandAckMapper;
import com.energyx.command.mapper.CommandMapper;
import com.energyx.command.model.CommandRow;
import com.energyx.command.model.DeviceInfo;
import com.energyx.command.mqtt.CommandKafkaProducer;
import com.energyx.command.web.dto.CreateCommandRequest;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.enums.CommandState;
import com.energyx.common.model.Result;
import com.energyx.common.redis.IdempotencyUtils;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2.1 物模型 Service 下发校验测试（command.model-check.mode：OFF/WARN/ENFORCE）。
 *
 * <p>
 * 覆盖：Service 白名单（identifier 匹配）、required
 * 缺失、未定义参数、dataType/enum/min/max/array/array-of-struct/ struct 缺字段校验；ENFORCE
 * 拒绝时不落库不发送，WARN 记录告警继续，OFF 完全不触发物模型调用。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CommandModelCheckTest {

	/** 覆盖 float+specs、enum、string+length、array、array-of-struct、struct 的服务定义 */
	private static final String SCHEMA = """
			{"services":[
			  {"identifier":"setPower","name":"调整功率","input":[
			    {"identifier":"power","dataType":"float","required":true,"specs":{"min":0,"max":1000}},
			    {"identifier":"mode","dataType":"enum","required":false,
			     "enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}]},
			    {"identifier":"target","dataType":"string","required":false,"specs":{"length":16}}
			  ]},
			  {"identifier":"setCells","name":"设置电芯","input":[
			    {"identifier":"cells","dataType":"array","required":true,"specs":{"elementType":"float","size":3}},
			    {"identifier":"points","dataType":"array","required":false,
			     "specs":{"elementType":"struct","structFields":[
			       {"identifier":"x","dataType":"float"},
			       {"identifier":"label","dataType":"string","specs":{"length":8}}
			     ]}}
			  ]},
			  {"identifier":"setEnv","name":"设置环境","input":[
			    {"identifier":"env","dataType":"struct","required":true,"specs":{"structFields":[
			      {"identifier":"temp","dataType":"float","specs":{"min":-40,"max":120}},
			      {"identifier":"note","dataType":"string","specs":{"length":8}}
			    ]}}
			  ]}
			]}
			""";

	@Mock
	CommandMapper commandMapper;

	@Mock
	CommandAckMapper ackMapper;

	@Mock
	DeviceFeignClient deviceFeignClient;

	@Mock
	ThingModelResolver thingModelResolver;

	@Mock
	StringRedisTemplate redis;

	@Mock
	CommandKafkaProducer producer;

	@Mock
	IdempotencyUtils idempotencyUtils;

	@Mock
	ListOperations<String, String> listOps;

	CommandService service;

	CommandProperties props;

	ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		props = new CommandProperties();
		lenient().when(redis.opsForList()).thenReturn(listOps);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private CommandService buildService(String mode) {
		props.setModelCheckMode(mode);
		return new CommandService(commandMapper, ackMapper, deviceFeignClient, thingModelResolver, redis, producer,
				objectMapper, idempotencyUtils, props, new SnowflakeIdGenerator());
	}

	private void mockModel() {
		when(thingModelResolver.resolve("pk-1")).thenReturn(parseModel());
	}

	private static ThingModel parseModel() {
		try {
			return ThingModelParser.parse(SCHEMA);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 在线直发成功路径的全部 mock（createCommand 正常返回 SENT；落库行命令名跟随请求） */
	private void stubCreateSuccess(String command) {
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
		when(commandMapper.insert(any(CommandRow.class))).thenReturn(1);
		when(commandMapper.selectById("c-1")).thenReturn(row(1, command));
		when(redis.hasKey("iot:online:100")).thenReturn(true);
	}

	private static CommandRow row(int state, String command) {
		CommandRow r = new CommandRow();
		r.setCommandId("c-1");
		r.setTenantId(1L);
		r.setDeviceId(100L);
		r.setProductKey("pk-1");
		r.setCommandName(command);
		r.setCommandType(2);
		r.setParams("{\"power\":50}");
		r.setState(CommandState.fromCode(state));
		r.setRetryCount(0);
		r.setMaxRetry(3);
		r.setTimeoutMs(15000);
		return r;
	}

	private static CreateCommandRequest req(String command, Map<String, Object> params) {
		CreateCommandRequest r = new CreateCommandRequest();
		r.setCommandId("c-1");
		r.setProductKey("pk-1");
		r.setDeviceName("dn-1");
		r.setCommand(command);
		r.setParams(params);
		return r;
	}

	private static void assertRejected(CommandService svc, CreateCommandRequest request, String keyword) {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> svc.createCommand(request));
		assertTrue(ex.getMessage().contains(keyword), "期望错误包含[" + keyword + "]，实际：" + ex.getMessage());
		assertTrue(ex.getMessage().contains("commandId=c-1"));
		assertTrue(ex.getMessage().contains("deviceId=100"));
		assertTrue(ex.getMessage().contains("productKey=pk-1"));
		assertTrue(ex.getMessage().contains("command=" + request.getCommand()));
	}

	// ------------------------------------------------------------------
	// OFF：完全不校验
	// ------------------------------------------------------------------

	@Test
	@DisplayName("OFF：不存在的 Service 可发送，且不触发物模型调用")
	void off_unknownServiceSends() {
		service = buildService("OFF");
		stubCreateSuccess("notInModel");

		service.createCommand(req("notInModel", Map.of("power", 50)));

		verify(thingModelResolver, never()).resolve(anyString());
		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("notInModel"));
	}

	@Test
	@DisplayName("OFF：非法 params 可发送")
	void off_invalidParamsSends() {
		service = buildService("OFF");
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", "abc", "ghost", 1)));

		verify(thingModelResolver, never()).resolve(anyString());
		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	// ------------------------------------------------------------------
	// WARN：校验 + 告警，不阻止
	// ------------------------------------------------------------------

	@Test
	@DisplayName("WARN：Service 存在且参数合法 → 正常发送")
	void warn_validSends() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", 50)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("WARN：Service 不存在 → 告警 + 继续发送")
	void warn_unknownServiceContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("ghostCmd");

		service.createCommand(req("ghostCmd", Map.of("power", 50)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("ghostCmd"));
	}

	@Test
	@DisplayName("WARN：required 缺失 → 告警 + 继续发送")
	void warn_missingRequiredContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("mode", 1))); // power 缺失

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("WARN：dataType 错误 → 告警 + 继续发送")
	void warn_badDataTypeContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", "abc")));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("WARN：enum 错误 → 告警 + 继续发送")
	void warn_badEnumContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", 50, "mode", 99)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("WARN：min/max 越界 → 告警 + 继续发送")
	void warn_outOfRangeContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", 12000)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("WARN：未定义参数 → 告警 + 继续发送")
	void warn_undefinedParamContinues() {
		service = buildService("WARN");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", 50, "ghost", 1)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	// ------------------------------------------------------------------
	// ENFORCE：校验失败拒绝，不落库不发送
	// ------------------------------------------------------------------

	@Test
	@DisplayName("ENFORCE：Service 存在且参数合法 → 正常发送")
	void enforce_validSends() {
		service = buildService("ENFORCE");
		mockModel();
		stubCreateSuccess("setPower");

		service.createCommand(req("setPower", Map.of("power", 50)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setPower"));
	}

	@Test
	@DisplayName("ENFORCE：Service 不存在 → 拒绝")
	void enforce_unknownServiceRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("ghostCmd", Map.of("power", 50)), "service 不存在");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(idempotencyUtils).release("c-1");
	}

	@Test
	@DisplayName("ENFORCE：required 缺失 → 拒绝")
	void enforce_missingRequiredRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("mode", 1)), "required 参数缺失");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：dataType 错误 → 拒绝")
	void enforce_badDataTypeRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("power", "abc")), "param=power");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：enum 错误 → 拒绝")
	void enforce_badEnumRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("power", 50, "mode", 99)), "enum 取值越界");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：min/max 越界 → 拒绝")
	void enforce_outOfRangeRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("power", 12000)), "大于 max");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：未定义参数 → 拒绝")
	void enforce_undefinedParamRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("power", 50, "ghost", 1)), "未定义参数");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：array 类型错误 → 拒绝")
	void enforce_arrayTypeErrorRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setCells", Map.of("cells", "not-array")), "array 类型需数组");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：array element 类型错误 → 拒绝")
	void enforce_arrayElementTypeErrorRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setCells", Map.of("cells", List.of("abc"))), "param=cells");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：array-of-struct 字段错误 → 拒绝")
	void enforce_arrayOfStructFieldErrorRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		// points[0] 缺 label、points[1].x 类型错误 → 逐元素 struct 递归拒绝
		assertRejected(service, req("setCells",
				Map.of("cells", List.of(1.0), "points", List.of(Map.of("x", 1.0), Map.of("x", "abc", "label", "ok")))),
				"param=points");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：struct 缺字段 → 拒绝")
	void enforce_structMissingFieldRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setEnv", Map.of("env", Map.of("note", "x"))), "struct 缺字段");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：struct 参数通过 → 正常发送")
	void enforce_structValidSends() {
		service = buildService("ENFORCE");
		mockModel();
		stubCreateSuccess("setEnv");

		service.createCommand(req("setEnv", Map.of("env", Map.of("temp", 25.5, "note", "ok"))));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("setEnv"));
	}

	@Test
	@DisplayName("ENFORCE：物模型缺失（Resolver 返回 null）→ 不阻止（兼容保障）")
	void enforce_missingModelSkipsCheck() {
		service = buildService("ENFORCE");
		when(thingModelResolver.resolve("pk-1")).thenReturn(null);
		stubCreateSuccess("ghostCmd");

		service.createCommand(req("ghostCmd", Map.of("power", 50)));

		verify(producer).send(eq(KafkaTopicConstant.IOT_COMMAND_DOWN), eq("100"), contains("ghostCmd"));
	}

	@Test
	@DisplayName("ENFORCE：字符串 length 超限 → 拒绝")
	void enforce_stringLengthRejected() {
		service = buildService("ENFORCE");
		mockModel();
		when(idempotencyUtils.tryAcquire("c-1", props.getIdempotencyTtlSeconds())).thenReturn(true);
		when(deviceFeignClient.byName("pk-1", "dn-1"))
			.thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));

		assertRejected(service, req("setPower", Map.of("power", 50, "target", "0123456789ABCDEFG")), "字符串超长");
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

}
