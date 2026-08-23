package com.energyx.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.command.client.DeviceFeignClient;
import com.energyx.command.client.ProductFeignClient;
import com.energyx.command.config.CommandProperties;
import com.energyx.command.mapper.CommandAckMapper;
import com.energyx.command.mapper.CommandMapper;
import com.energyx.command.model.CommandRow;
import com.energyx.command.model.DeviceInfo;
import com.energyx.command.mqtt.CommandKafkaProducer;
import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.enums.CommandState;
import com.energyx.common.message.ShadowDeltaMessage;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2.3 delta → setProperties 物化物模型校验测试（复用
 * energyx.command.model-check.mode：OFF/WARN/ENFORCE）。
 *
 * <p>
 * 覆盖：未定义/r/enum/range/struct/array-of-struct 校验；ENFORCE 拒绝时不落库不 dispatch；
 * 在途合并、设备不存在、物模型缺失等兼容路径；OFF 不调用 product Feign。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DeltaModelCheckTest {

	/** 与 M2.2 测试一致的属性集（rw/w/r/enum/specs/struct/array-of-struct） */
	private static final String SCHEMA = """
			{"properties":[
			  {"identifier":"power","dataType":"float","accessMode":"rw","specs":{"min":0,"max":1000}},
			  {"identifier":"soc","dataType":"float","accessMode":"r","specs":{"min":0,"max":100}},
			  {"identifier":"target","dataType":"string","accessMode":"w","specs":{"length":16}},
			  {"identifier":"mode","dataType":"enum","accessMode":"rw",
			   "enumValues":[{"value":0},{"value":1},{"value":2}]},
			  {"identifier":"env","dataType":"struct","accessMode":"rw","specs":{"structFields":[
			    {"identifier":"temp","dataType":"float","specs":{"min":-40,"max":120}},
			    {"identifier":"note","dataType":"string","specs":{"length":8}}
			  ]}},
			  {"identifier":"points","dataType":"array","accessMode":"rw",
			   "specs":{"elementType":"struct","structFields":[
			     {"identifier":"x","dataType":"float"},
			     {"identifier":"label","dataType":"string","specs":{"length":8}}
			   ]}}
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

	private void mockDeviceAndModel() {
		// byId 可能被 stubMaterializeSuccess 覆盖（重复 stubbing），用 lenient 避免 strict 误报
		lenient().when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
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

	/** materializeDelta 成功物化（离线入队路径）的全部 mock */
	private void stubMaterializeSuccess() {
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);
		when(commandMapper.insert(any(CommandRow.class))).thenReturn(1);
		when(commandMapper.selectById(anyString())).thenReturn(materializedRow());
		when(redis.hasKey("iot:online:100")).thenReturn(false);
		when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);
	}

	private static CommandRow materializedRow() {
		CommandRow r = new CommandRow();
		r.setCommandId("c-delta");
		r.setTenantId(1L);
		r.setDeviceId(100L);
		r.setProductKey("pk-1");
		r.setCommandName("setProperties");
		r.setCommandType(2);
		r.setParams("{\"power\":100}");
		r.setState(CommandState.CREATED);
		r.setRetryCount(0);
		r.setMaxRetry(3);
		r.setTimeoutMs(15000);
		return r;
	}

	private static ShadowDeltaMessage delta(Map<String, Object> desired) {
		ShadowDeltaMessage d = new ShadowDeltaMessage();
		d.setDeviceId(100L);
		d.setTenantId(1L);
		d.setVersion(2);
		d.setDesired(desired);
		d.setTs(System.currentTimeMillis());
		return d;
	}

	private static Map<String, Object> desired(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	/** ENFORCE 拒绝统一断言：抛错 + message 含定位信息（零副作用由各用例 verify） */
	private static void assertEnforceRejected(CommandService svc, ShadowDeltaMessage delta, String keyword) {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> svc.materializeDelta(delta));
		assertTrue(ex.getMessage().contains(keyword), "期望错误包含[" + keyword + "]，实际：" + ex.getMessage());
		assertTrue(ex.getMessage().contains("deviceId=100"));
		assertTrue(ex.getMessage().contains("productKey=pk-1"));
	}

	// ------------------------------------------------------------------
	// OFF
	// ------------------------------------------------------------------

	@Test
	@DisplayName("OFF：不调用 product Feign，非法 delta 照常物化")
	void off_skipsModelCheckAndMaterializes() {
		service = buildService("OFF");
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("ghost", 1, "soc", 50)));

		verify(thingModelResolver, never()).resolve(anyString());
		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	// ------------------------------------------------------------------
	// WARN：告警后仍物化
	// ------------------------------------------------------------------

	@Test
	@DisplayName("WARN：未定义属性 → 告警 + 仍物化")
	void warn_undefinedPropertyMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("ghost", 1)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：r 属性 → 告警 + 仍物化")
	void warn_readOnlyPropertyMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("soc", 50)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：enum 非法 → 告警 + 仍物化")
	void warn_badEnumMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("mode", 99)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：range 越界 → 告警 + 仍物化")
	void warn_outOfRangeMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("power", 12000)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：struct 非法 → 告警 + 仍物化")
	void warn_badStructMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("env", Map.of("note", "x"))));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：array-of-struct 非法 → 告警 + 仍物化")
	void warn_badArrayOfStructMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("points", List.of(Map.of("x", 1.0)))));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("WARN：多属性一个非法 → 整体仍物化")
	void warn_multiPropertyOneInvalidMaterializes() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("power", 100, "soc", 50)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	// ------------------------------------------------------------------
	// ENFORCE：拒绝 + 零副作用
	// ------------------------------------------------------------------

	@Test
	@DisplayName("ENFORCE：未定义属性 → 拒绝且不落库不 dispatch")
	void enforce_undefinedPropertyRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("ghost", 1)), "属性不存在");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(listOps, never()).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：r 属性 → 拒绝且不落库不 dispatch")
	void enforce_readOnlyPropertyRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("soc", 50)), "只读属性不可写");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(listOps, never()).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：enum 非法 → 拒绝且不落库不 dispatch")
	void enforce_badEnumRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("mode", 99)), "enum 取值越界");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：类型错误 → 拒绝且不落库不 dispatch")
	void enforce_badTypeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("power", "abc")), "For input string");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：range 越界 → 拒绝且不落库不 dispatch")
	void enforce_outOfRangeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("power", 12000)), "大于 max");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：struct 缺字段 → 拒绝且不落库不 dispatch")
	void enforce_badStructRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("env", Map.of("note", "x"))), "struct 缺字段");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：array-of-struct 字段错误 → 拒绝且不落库不 dispatch")
	void enforce_badArrayOfStructRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("points", List.of(Map.of("x", 1.0)))), "struct 缺字段");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：多属性一个非法 → 整个 delta 拒绝")
	void enforce_multiPropertyOneInvalidWholeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn(null);

		assertEnforceRejected(service, delta(desired("power", 100, "soc", 50)), "只读属性不可写");
		verify(commandMapper, never()).insert(any(CommandRow.class));
		verify(producer, never()).send(anyString(), anyString(), anyString());
		verify(listOps, never()).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("ENFORCE：全部合法 → 正常物化")
	void enforce_allValidMaterializes() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("power", 100, "mode", 1, "target", "ok")));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	// ------------------------------------------------------------------
	// 兼容路径
	// ------------------------------------------------------------------

	@Test
	@DisplayName("在途 setProperties → 合并跳过，不触发物模型校验")
	void inflightCoalesce_skipsModelCheck() {
		service = buildService("ENFORCE");
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
		when(commandMapper.selectInFlightByDeviceAndName(100L, "setProperties")).thenReturn("existing");

		service.materializeDelta(delta(desired("ghost", 1)));

		verify(thingModelResolver, never()).resolve(anyString());
		verify(commandMapper, never()).insert(any(CommandRow.class));
	}

	@Test
	@DisplayName("设备不存在 → 跳过（现有行为）")
	void deviceNotFound_skips() {
		service = buildService("ENFORCE");
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(null));

		service.materializeDelta(delta(desired("ghost", 1)));

		verify(thingModelResolver, never()).resolve(anyString());
		verify(commandMapper, never()).insert(any(CommandRow.class));
	}

	@Test
	@DisplayName("物模型缺失（Feign 失败/未发布）→ 跳过校验放行，不阻塞物化")
	void modelMissing_skipsCheckAndMaterializes() {
		service = buildService("ENFORCE");
		when(thingModelResolver.resolve("pk-1")).thenReturn(null);
		stubMaterializeSuccess();

		service.materializeDelta(delta(desired("ghost", 1)));

		verify(commandMapper).insert(any(CommandRow.class));
		verify(listOps).rightPush(anyString(), anyString());
	}

	@Test
	@DisplayName("delta 缺 deviceId/desired → 直接返回")
	void emptyDelta_skips() {
		service = buildService("ENFORCE");

		service.materializeDelta(new ShadowDeltaMessage());

		verify(deviceFeignClient, never()).byId(anyLong());
		verify(thingModelResolver, never()).resolve(anyString());
	}

}
