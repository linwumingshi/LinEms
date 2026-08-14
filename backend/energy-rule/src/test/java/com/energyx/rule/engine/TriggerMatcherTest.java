package com.energyx.rule.engine;

import com.energyx.rule.model.RuleDevice;
import com.energyx.rule.model.RuleTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 触发器匹配器测试（PROPERTY 数值比较 / 设备命中 / LIFECYCLE / ALARM / 产品级 deviceName 为空）。
 */
class TriggerMatcherTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final TriggerMatcher matcher = new TriggerMatcher(redis);

	@SuppressWarnings("unchecked")
	private void mockDeviceCache(Long deviceId) {
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(anyString()))
			.thenReturn(deviceId == null ? null : "{\"deviceId\":" + deviceId + ",\"deviceType\":\"pcs\"}");
	}

	private RuleContext ctx(long deviceId, String productKey, Map<String, Object> props) {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("PROPERTY");
		ctx.setDeviceId(deviceId);
		ctx.setProductKey(productKey);
		ctx.setProperties(props == null ? new LinkedHashMap<>() : props);
		return ctx;
	}

	private RuleTrigger propTrigger(String productKey, String deviceName, String property, String op, Object value) {
		RuleTrigger t = new RuleTrigger();
		t.setType("PROPERTY");
		RuleDevice d = new RuleDevice();
		d.setProductKey(productKey);
		d.setDeviceName(deviceName);
		t.setDevice(d);
		t.setProperty(property);
		t.setOp(op);
		t.setValue(value);
		return t;
	}

	@Test
	@DisplayName("PROPERTY：温度 52 GT 50 命中（数值比较）")
	void propertyGtHit() {
		mockDeviceCache(10L);
		RuleContext ctx = ctx(10L, "energyx_pcs", Map.of("cellTemp", 52));
		assertTrue(matcher.matches(propTrigger("energyx_pcs", "PCS-001", "cellTemp", "GT", 50), ctx));
	}

	@Test
	@DisplayName("PROPERTY：温度 45 GT 50 不命中")
	void propertyGtMiss() {
		mockDeviceCache(10L);
		RuleContext ctx = ctx(10L, "energyx_pcs", Map.of("cellTemp", 45));
		assertFalse(matcher.matches(propTrigger("energyx_pcs", "PCS-001", "cellTemp", "GT", 50), ctx));
	}

	@Test
	@DisplayName("PROPERTY：deviceName 为空=产品级，产品匹配即命中")
	void productLevelHit() {
		RuleContext ctx = ctx(10L, "energyx_pcs", Map.of("soc", 80));
		assertTrue(matcher.matches(propTrigger("energyx_pcs", null, "soc", "GTE", 50), ctx));
	}

	@Test
	@DisplayName("PROPERTY：产品不匹配不命中")
	void productMismatch() {
		mockDeviceCache(10L);
		RuleContext ctx = ctx(10L, "energyx_pcs", Map.of("cellTemp", 60));
		assertFalse(matcher.matches(propTrigger("energyx_bms", "PCS-001", "cellTemp", "GT", 50), ctx));
	}

	@Test
	@DisplayName("PROPERTY：EQ 字符串相等（状态枚举）")
	void propertyEqString() {
		mockDeviceCache(10L);
		RuleContext ctx = ctx(10L, "energyx_pcs", Map.of("runState", "RUNNING"));
		assertTrue(matcher.matches(propTrigger("energyx_pcs", "PCS-001", "runState", "EQ", "RUNNING"), ctx));
	}

	@Test
	@DisplayName("LIFECYCLE：OFFLINE 事件匹配")
	void lifecycleHit() {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("LIFECYCLE");
		ctx.setDeviceId(10L);
		ctx.setProductKey("energyx_pcs");
		ctx.setLifecycleEvent("OFFLINE");
		RuleTrigger t = new RuleTrigger();
		t.setType("LIFECYCLE");
		t.setEvent("OFFLINE");
		assertTrue(matcher.matches(t, ctx));
	}

	@Test
	@DisplayName("LIFECYCLE：事件不匹配不命中")
	void lifecycleMiss() {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("LIFECYCLE");
		ctx.setDeviceId(10L);
		ctx.setProductKey("energyx_pcs");
		ctx.setLifecycleEvent("ONLINE");
		RuleTrigger t = new RuleTrigger();
		t.setType("LIFECYCLE");
		t.setEvent("OFFLINE");
		assertFalse(matcher.matches(t, ctx));
	}

	@Test
	@DisplayName("ALARM：ruleCode + level + ACTIVE 全部匹配")
	void alarmHit() {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("ALARM");
		ctx.setDeviceId(10L);
		ctx.setAlarm(Map.of("code", "SCENE_TEMP_HIGH", "level", 3, "state", "ACTIVE"));
		RuleTrigger t = new RuleTrigger();
		t.setType("ALARM");
		t.setAlarmCode("SCENE_TEMP_HIGH");
		t.setLevel(3);
		t.setState("ACTIVE");
		assertTrue(matcher.matches(t, ctx));
	}

	@Test
	@DisplayName("ALARM：level 不匹配不命中")
	void alarmLevelMiss() {
		RuleContext ctx = new RuleContext();
		ctx.setTriggerType("ALARM");
		ctx.setDeviceId(10L);
		ctx.setAlarm(Map.of("code", "SCENE_TEMP_HIGH", "level", 2, "state", "ACTIVE"));
		RuleTrigger t = new RuleTrigger();
		t.setType("ALARM");
		t.setAlarmCode("SCENE_TEMP_HIGH");
		t.setLevel(3);
		assertFalse(matcher.matches(t, ctx));
	}

	@Test
	@DisplayName("TIMER/MANUAL 不在 TriggerMatcher 判定（外部驱动）")
	void externalDrivenNotMatchedHere() {
		RuleTrigger timer = new RuleTrigger();
		timer.setType("TIMER");
		timer.setCron("0 30 22 * * ?");
		assertFalse(matcher.matches(timer, new RuleContext()));
	}

}
