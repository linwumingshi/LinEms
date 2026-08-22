package com.energyx.access.model;

import com.energyx.common.enums.EventSeverity;
import com.energyx.common.thingmodel.EnumValue;
import com.energyx.common.thingmodel.ModelValidator;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物模型上报校验 + 类型强转测试。
 */
class ModelValidatorTest {

	private ThingModel model;

	@BeforeEach
	void setUp() throws Exception {
		model = ThingModelParser
			.parse("""
					{"properties":[
					   {"identifier":"soc","name":"荷电状态","dataType":"float","accessMode":"r","required":true},
					   {"identifier":"runMode","name":"运行模式","dataType":"enum",
					    "enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}],"accessMode":"rw"},
					   {"identifier":"alarm","name":"告警开关","dataType":"bool","accessMode":"r"},
					   {"identifier":"unitNo","name":"柜号","dataType":"string","accessMode":"r"}
					 ],
					 "events":[
					   {"identifier":"overTemp","name":"过温告警","type":"WARN"},
					   {"identifier":"bmsFault","name":"BMS故障","type":"ERROR"}
					 ]}
					""");
	}

	@Test
	void coerce_float_shouldAcceptNumberAndNumericString() {
		ThingModelProperty soc = model.getProperties().get("soc");
		assertEquals(85.2, ModelValidator.coerce(soc, 85.2));
		assertEquals(85.5, ModelValidator.coerce(soc, "85.5"));
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(soc, "abc"));
	}

	@Test
	void coerce_bool_shouldAcceptTrueFalseOneZero() {
		ThingModelProperty alarm = model.getProperties().get("alarm");
		assertEquals(true, ModelValidator.coerce(alarm, true));
		assertEquals(true, ModelValidator.coerce(alarm, "1"));
		assertEquals(true, ModelValidator.coerce(alarm, "true"));
		assertEquals(false, ModelValidator.coerce(alarm, "0"));
		assertEquals(false, ModelValidator.coerce(alarm, "false"));
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(alarm, "maybe"));
	}

	@Test
	void coerce_enum_shouldAcceptInRangeAndRejectOutOfRange() {
		ThingModelProperty runMode = model.getProperties().get("runMode");
		assertEquals(1, ModelValidator.coerce(runMode, 1));
		assertEquals(2, ModelValidator.coerce(runMode, "2"));
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(runMode, 5));
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(runMode, "99"));
	}

	@Test
	void coerce_string_shouldStringify() {
		ThingModelProperty unitNo = model.getProperties().get("unitNo");
		assertEquals("E-1", ModelValidator.coerce(unitNo, "E-1"));
	}

	@Test
	void validateProperties_shouldCoerceKnownAndRejectUnknown() {
		Map<String, Object> reported = new LinkedHashMap<>();
		reported.put("soc", "85.2");
		reported.put("runMode", 1);
		reported.put("ghost", 42); // 未在物模型登记

		ModelValidator.ValidationResult result = ModelValidator.validateProperties(model, reported);

		assertFalse(result.valid());
		assertEquals(1, result.errors().size());
		assertTrue(result.errors().get(0).contains("ghost"));
		// 合法属性仍被强转保留
		assertEquals(85.2, result.coerced().get("soc"));
		assertEquals(1, ((Number) result.coerced().get("runMode")).intValue());
		assertNull(result.coerced().get("ghost"));
	}

	@Test
	void validateProperties_allValid_shouldPass() {
		Map<String, Object> reported = new LinkedHashMap<>();
		reported.put("soc", 85.2);
		reported.put("runMode", 2);

		ModelValidator.ValidationResult result = ModelValidator.validateProperties(model, reported);

		assertTrue(result.valid());
		assertEquals(2, result.coerced().size());
	}

	@Test
	void validateProperties_emptyReport_shouldPass() {
		ModelValidator.ValidationResult result = ModelValidator.validateProperties(model, new LinkedHashMap<>());
		assertTrue(result.valid());
		assertTrue(result.coerced().isEmpty());
	}

	@Test
	void checkEvent_shouldMapSeverity() {
		assertNotNull(ModelValidator.checkEvent(model, "overTemp"));
		assertEquals(2, ModelValidator.checkEvent(model, "overTemp").severity()); // WARN→2
		assertEquals(3, ModelValidator.checkEvent(model, "bmsFault").severity()); // ERROR→3
		assertNull(ModelValidator.checkEvent(model, "notExists"));
	}

	@Test
	void severityOf_shouldMapAllLevels() {
		assertEquals(1, ModelValidator.severityOf(EventSeverity.INFO));
		assertEquals(2, ModelValidator.severityOf(EventSeverity.WARN));
		assertEquals(3, ModelValidator.severityOf(EventSeverity.ERROR));
		assertEquals(2, ModelValidator.severityOf(null));
	}

	@Test
	void coerce_shouldReturnListForStructProperty() {
		ThingModelProperty struct = new ThingModelProperty();
		struct.setIdentifier("ext");
		struct.setDataType("struct");
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("a", 1);
		Object coerced = ModelValidator.coerce(struct, nested);
		assertTrue(coerced instanceof Map);
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(struct, "not-object"));
	}

	// ------------------------------------------------------------------
	// M1 specs 深度校验
	// ------------------------------------------------------------------

	private ThingModelProperty numericProp(String identifier, String dataType, Double min, Double max, Double step) {
		ThingModelProperty prop = new ThingModelProperty();
		prop.setIdentifier(identifier);
		prop.setDataType(dataType);
		prop.setMin(min);
		prop.setMax(max);
		prop.setStep(step);
		return prop;
	}

	@Test
	void coerce_min_shouldAcceptInRangeAndRejectBelow() {
		ThingModelProperty soc = numericProp("soc", "float", 0.0, 100.0, null);
		assertEquals(50.5, ModelValidator.coerce(soc, 50.5));
		assertEquals(0.0, ModelValidator.coerce(soc, 0));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(soc, -1));
		assertTrue(ex.getMessage().contains("小于 min"));
	}

	@Test
	void coerce_max_shouldAcceptInRangeAndRejectAbove() {
		ThingModelProperty soc = numericProp("soc", "float", 0.0, 100.0, null);
		assertEquals(100.0, ModelValidator.coerce(soc, 100));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(soc, 100.5));
		assertTrue(ex.getMessage().contains("大于 max"));
	}

	@Test
	void coerce_intMinMax_shouldCheckAfterCoercion() {
		ThingModelProperty level = numericProp("level", "int", 1.0, 5.0, null);
		assertEquals(3L, ModelValidator.coerce(level, "3")); // 字符串先强转 long 再范围判断
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(level, "6"));
	}

	@Test
	void coerce_step_shouldAcceptOnStepAndRejectOffStep() {
		ThingModelProperty power = numericProp("power", "float", 0.0, null, 10.0);
		assertEquals(20.0, ModelValidator.coerce(power, 20));
		assertEquals(0.0, ModelValidator.coerce(power, 0));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(power, 15));
		assertTrue(ex.getMessage().contains("步长"));
	}

	@Test
	void coerce_stepWithoutMin_shouldSkipStepCheck() {
		// specs 部分存在：仅 step 无 min → 步长无锚点，跳过校验（明确策略）
		ThingModelProperty power = numericProp("power", "float", null, null, 10.0);
		assertEquals(15.0, ModelValidator.coerce(power, 15));
	}

	@Test
	void coerce_stringLength_shouldAcceptWithinAndRejectOver() {
		ThingModelProperty unitNo = new ThingModelProperty();
		unitNo.setIdentifier("unitNo");
		unitNo.setDataType("string");
		unitNo.setLength(4);
		assertEquals("E-01", ModelValidator.coerce(unitNo, "E-01"));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(unitNo, "E-12345"));
		assertTrue(ex.getMessage().contains("超长"));
	}

	@Test
	void coerce_arrayElement_shouldCoerceEachElement() {
		ThingModelProperty cells = new ThingModelProperty();
		cells.setIdentifier("cells");
		cells.setDataType("array");
		cells.setElementType("float");
		List<Object> raw = new java.util.ArrayList<>();
		raw.add("1.5");
		raw.add(2);
		Object coerced = ModelValidator.coerce(cells, raw);
		assertTrue(coerced instanceof List);
		assertEquals(1.5, ((List<?>) coerced).get(0));
		assertEquals(2.0, ((List<?>) coerced).get(1));
	}

	@Test
	void coerce_arrayElementTypeError_shouldReject() {
		ThingModelProperty cells = new ThingModelProperty();
		cells.setIdentifier("cells");
		cells.setDataType("array");
		cells.setElementType("int");
		List<Object> raw = new java.util.ArrayList<>();
		raw.add("abc");
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(cells, raw));
	}

	@Test
	void coerce_arraySize_shouldRejectOverLimit() {
		ThingModelProperty cells = new ThingModelProperty();
		cells.setIdentifier("cells");
		cells.setDataType("array");
		cells.setSize(2);
		List<Object> raw = new java.util.ArrayList<>();
		raw.add(1);
		raw.add(2);
		raw.add(3);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(cells, raw));
		assertTrue(ex.getMessage().contains("超长"));
	}

	@Test
	void coerce_arrayNonList_shouldReject() {
		ThingModelProperty cells = new ThingModelProperty();
		cells.setIdentifier("cells");
		cells.setDataType("array");
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(cells, "not-array"));
	}

	@Test
	void coerce_arrayWithoutSpecs_shouldPassThrough() {
		// 历史行为：array 无 elementType/size 时不新增校验（透传）
		ThingModelProperty cells = new ThingModelProperty();
		cells.setIdentifier("cells");
		cells.setDataType("array");
		List<Object> raw = new java.util.ArrayList<>();
		raw.add("x");
		assertSame(raw, ModelValidator.coerce(cells, raw));
	}

	@Test
	void coerce_structFields_shouldValidateEachField() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"env","name":"环境量","dataType":"struct",
				  "specs":{"structFields":[
				    {"identifier":"temp","dataType":"float","specs":{"min":-40,"max":120}},
				    {"identifier":"label","dataType":"string","specs":{"length":16}}
				  ]}}]}
				""");
		ThingModelProperty env = model.getProperties().get("env");
		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put("temp", "25.5");
		ok.put("label", "A-1");
		Object coerced = ModelValidator.coerce(env, ok);
		assertTrue(coerced instanceof Map);
		Map<?, ?> coercedMap = (Map<?, ?>) coerced;
		assertEquals(25.5, coercedMap.get("temp"));
		assertEquals("A-1", coercedMap.get("label"));

		Map<String, Object> missing = new LinkedHashMap<>();
		missing.put("temp", 25.5);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ModelValidator.coerce(env, missing));
		assertTrue(ex.getMessage().contains("缺字段"));

		Map<String, Object> outOfRange = new LinkedHashMap<>();
		outOfRange.put("temp", 200);
		outOfRange.put("label", "A-1");
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(env, outOfRange));
	}

	@Test
	void coerce_structWithoutFields_shouldRequireObjectOnly() {
		// 历史行为：struct 无 structFields 时仅要求对象
		ThingModelProperty struct = new ThingModelProperty();
		struct.setIdentifier("ext");
		struct.setDataType("struct");
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("a", 1);
		assertSame(nested, ModelValidator.coerce(struct, nested));
	}

	@Test
	void coerce_noSpecs_shouldKeepLegacyBehavior() {
		// 无 specs 的历史模型：范围/长度不校验，类型强转与透传行为不变
		ThingModelProperty soc = numericProp("soc", "float", null, null, null);
		assertEquals(999.0, ModelValidator.coerce(soc, 999)); // 无 min/max 不拒绝
		ThingModelProperty unitNo = new ThingModelProperty();
		unitNo.setIdentifier("unitNo");
		unitNo.setDataType("string");
		assertEquals("very-long-string-over-any-limit",
				ModelValidator.coerce(unitNo, "very-long-string-over-any-limit"));
		assertThrows(IllegalArgumentException.class, () -> ModelValidator.coerce(soc, "abc")); // 类型强转仍生效
	}

}
