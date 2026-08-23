package com.energyx.shadow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.model.Result;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.shadow.client.DeviceFeignClient;
import com.energyx.shadow.client.DeviceInfo;
import com.energyx.shadow.config.ShadowProperties;
import com.energyx.shadow.delta.ShadowDeltaPublisher;
import com.energyx.shadow.mapper.ShadowHistoryMapper;
import com.energyx.shadow.mapper.ShadowMapper;
import com.energyx.shadow.model.ShadowRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2.2 Shadow desired 物模型校验测试（energyx.shadow.model-check.mode：OFF/WARN/ENFORCE）。
 *
 * <p>
 * 覆盖：未定义属性、r 属性拒绝、enum/min-max/length/struct/array/array-of-struct、多属性原子语义； OFF 不调用
 * Feign；WARN 告警后仍写入；ENFORCE 拒绝时 MySQL/Redis/delta/history 零副作用。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ShadowModelCheckTest {

	/** 覆盖 rw/w/r/enum/specs/struct/array-of-struct 的属性集 */
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
	ShadowMapper shadowMapper;

	@Mock
	ShadowHistoryMapper historyMapper;

	@Mock
	StringRedisTemplate redis;

	@Mock
	ShadowDeltaPublisher deltaPublisher;

	@Mock
	DeviceFeignClient deviceFeignClient;

	@Mock
	ThingModelResolver thingModelResolver;

	@Mock
	HashOperations<String, Object, Object> hashOps;

	@Mock
	ValueOperations<String, String> valueOps;

	ShadowService service;

	@BeforeEach
	void setUp() {
		lenient().when(redis.opsForValue()).thenReturn(valueOps);
		lenient().when(redis.opsForHash()).thenReturn(hashOps);
		lenient().when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private ShadowService buildService(String mode) {
		ShadowProperties props = new ShadowProperties();
		props.setHistoryEnabled(true);
		props.setHistoryThrottleSeconds(60);
		props.setOptimisticMaxRetry(3);
		props.setReportedTtlDays(7);
		props.setModelCheckMode(mode);
		return new ShadowService(shadowMapper, historyMapper, redis, deltaPublisher, new ObjectMapper(), props,
				deviceFeignClient, thingModelResolver);
	}

	private void mockDeviceAndModel() {
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
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

	/** setDesired 成功写入路径的全部 mock（校验通过后原链路执行） */
	private void stubWriteSuccess() {
		ShadowRow row = new ShadowRow();
		row.setDeviceId(100L);
		row.setReported("{}");
		row.setDesired("{}");
		row.setVersion(2);
		when(shadowMapper.selectById(100L)).thenReturn(row);
		when(shadowMapper.updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class))).thenReturn(1);
		when(hashOps.entries("iot:shadow:reported:100")).thenReturn(Map.of());
	}

	private static Map<String, Object> desired(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	/** ENFORCE 拒绝统一断言：抛错且 message 含定位信息（零副作用由各用例 verify 断言） */
	private static void assertEnforceRejected(ShadowService svc, Map<String, Object> desired, String keyword) {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> svc.setDesired(100L, 1L, desired));
		assertTrue(ex.getMessage().contains(keyword), "期望错误包含[" + keyword + "]，实际：" + ex.getMessage());
		assertTrue(ex.getMessage().contains("deviceId=100"));
		assertTrue(ex.getMessage().contains("productKey=pk-1"));
	}

	// ------------------------------------------------------------------
	// OFF
	// ------------------------------------------------------------------

	@Test
	@DisplayName("OFF：不调用 Device/Product Feign，非法属性照常写入")
	void off_skipsFeignAndWrites() {
		service = buildService("OFF");
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("ghost", 1, "soc", 50));

		verify(deviceFeignClient, never()).byId(anyLong());
		verify(thingModelResolver, never()).resolve(anyString());
		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	// ------------------------------------------------------------------
	// WARN：告警后仍写入
	// ------------------------------------------------------------------

	@Test
	@DisplayName("WARN：未定义属性 → 告警 + 仍写入")
	void warn_undefinedPropertyWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("ghost", 1));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：r 属性 → 告警 + 仍写入")
	void warn_readOnlyPropertyWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("soc", 50));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：enum 非法 → 告警 + 仍写入")
	void warn_badEnumWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("mode", 99));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：range 越界 → 告警 + 仍写入")
	void warn_outOfRangeWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("power", 12000));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：struct 非法 → 告警 + 仍写入")
	void warn_badStructWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("env", Map.of("note", "x")));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：array-of-struct 非法 → 告警 + 仍写入")
	void warn_badArrayOfStructWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("points", List.of(Map.of("x", 1.0))));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("WARN：多属性一个非法 → 整体仍写入")
	void warn_multiPropertyOneInvalidWrites() {
		service = buildService("WARN");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("power", 100, "soc", 50));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	// ------------------------------------------------------------------
	// ENFORCE：拒绝 + 零副作用
	// ------------------------------------------------------------------

	@Test
	@DisplayName("ENFORCE：未定义属性 → 拒绝且零副作用")
	void enforce_undefinedPropertyRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.setDesired(100L, 1L, desired("ghost", 1)));
		assertTrue(ex.getMessage().contains("属性不存在"));
		assertTrue(ex.getMessage().contains("deviceId=100"));
		assertTrue(ex.getMessage().contains("productKey=pk-1"));
		verify(shadowMapper, never()).insertDesired(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
		verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
	}

	@Test
	@DisplayName("ENFORCE：r 属性 → 拒绝且零副作用")
	void enforce_readOnlyPropertyRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("soc", 50), "只读属性不可写");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
		verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
	}

	@Test
	@DisplayName("ENFORCE：enum 非法 → 拒绝且零副作用")
	void enforce_badEnumRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("mode", 99), "enum 取值越界");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
		verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
	}

	@Test
	@DisplayName("ENFORCE：类型错误 → 拒绝且零副作用")
	void enforce_badTypeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("power", "abc"), "For input string");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("ENFORCE：range 越界 → 拒绝且零副作用")
	void enforce_outOfRangeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("power", 12000), "大于 max");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("ENFORCE：struct 缺字段 → 拒绝且零副作用")
	void enforce_badStructRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("env", Map.of("note", "x")), "struct 缺字段");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("ENFORCE：array 类型错误 → 拒绝且零副作用")
	void enforce_badArrayRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("points", "not-array"), "array 类型需数组");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("ENFORCE：array-of-struct 字段错误 → 拒绝且零副作用")
	void enforce_badArrayOfStructRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		assertEnforceRejected(service, desired("points", List.of(Map.of("x", 1.0))), "struct 缺字段");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("ENFORCE：多属性一个非法 → 整个请求拒绝（power 也不写入）")
	void enforce_multiPropertyOneInvalidWholeRejected() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();

		// power 合法、soc 只读 → 整体拒绝，power 不得提前写入
		assertEnforceRejected(service, desired("power", 100, "soc", 50), "只读属性不可写");
		verify(shadowMapper, never()).updateDesired(anyLong(), anyString(), anyInt(), any(LocalDateTime.class));
		verify(hashOps, never()).put(eq("iot:shadow:desired:100"), anyString(), anyString());
		verify(deltaPublisher, never()).publish(anyLong(), anyLong(), anyInt(), any());
		verify(historyMapper, never()).insert(anyLong(), anyInt(), anyString(), anyInt());
	}

	@Test
	@DisplayName("ENFORCE：全部合法 → 正常写入")
	void enforce_allValidWrites() {
		service = buildService("ENFORCE");
		mockDeviceAndModel();
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("power", 100, "mode", 1, "target", "ok"));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("ENFORCE：物模型缺失（Feign 失败/未发布）→ 跳过校验不阻塞")
	void enforce_missingModelSkipsCheck() {
		service = buildService("ENFORCE");
		when(deviceFeignClient.byId(100L)).thenReturn(Result.ok(new DeviceInfo(100L, 1L, "pk-1", "dn-1", 3)));
		when(thingModelResolver.resolve("pk-1")).thenReturn(null);
		stubWriteSuccess();

		service.setDesired(100L, 1L, desired("ghost", 1));

		verify(shadowMapper).updateDesired(eq(100L), anyString(), eq(2), any(LocalDateTime.class));
	}

}
