package com.energyx.common.thingmodel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物模型 Service 入参校验器测试（M2.1）。
 *
 * <p>
 * 覆盖：required 缺失、未定义参数、dataType/enum/min/max/length/array/array-of-struct/struct 递归校验。
 * </p>
 */
class ThingModelServiceValidatorTest {

	private static final String SCHEMA = """
			{"services":[
			  {"identifier":"setPower","name":"调整功率","input":[
			    {"identifier":"power","dataType":"float","required":true,"specs":{"min":0,"max":1000}},
			    {"identifier":"mode","dataType":"enum","required":false,
			     "enumValues":[{"value":0},{"value":1},{"value":2}]},
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

	private ThingModelService setPower;

	private ThingModelService setCells;

	private ThingModelService setEnv;

	@BeforeEach
	void setUp() throws Exception {
		ThingModel model = ThingModelParser.parse(SCHEMA);
		setPower = model.getServices().get("setPower");
		setCells = model.getServices().get("setCells");
		setEnv = model.getServices().get("setEnv");
	}

	private static Map<String, Object> params(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	@Test
	void validParams_shouldPassAndCoerce() {
		ThingModelServiceValidator.ServiceValidationResult r = ThingModelServiceValidator.validateParams(setPower,
				params("power", "50.5", "mode", 1));
		assertTrue(r.valid());
		assertEquals(50.5, ((Number) r.coerced().get("power")).doubleValue());
		assertEquals(1, ((Number) r.coerced().get("mode")).intValue());
	}

	@Test
	void missingRequired_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("mode", 1));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("required 参数缺失") && e.contains("param=power")));
	}

	@Test
	void missingOptional_shouldPass() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", 10));
		assertTrue(r.valid());
	}

	@Test
	void nullRequiredValue_shouldFail() {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("power", null);
		var r = ThingModelServiceValidator.validateParams(setPower, p);
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("required 参数缺失")));
	}

	@Test
	void undefinedParam_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", 10, "ghost", 1));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("未定义参数") && e.contains("param=ghost")));
	}

	@Test
	void badDataType_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", "abc"));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("param=power")));
	}

	@Test
	void badEnum_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", 10, "mode", 99));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("enum 取值越界")));
	}

	@Test
	void outOfRange_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", 12000));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("大于 max")));
	}

	@Test
	void stringOverLength_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setPower, params("power", 10, "target", "0123456789ABCDEFG"));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("字符串超长")));
	}

	@Test
	void arrayTypeError_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setCells, params("cells", "not-array"));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("array 类型需数组")));
	}

	@Test
	void arrayElementTypeError_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setCells, params("cells", List.of("abc")));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("param=cells")));
	}

	@Test
	void arraySizeOverLimit_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setCells, params("cells", List.of(1.0, 2.0, 3.0, 4.0)));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("array 超长")));
	}

	@Test
	void arrayOfStruct_missingField_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setCells,
				params("cells", List.of(1.0), "points", List.of(Map.of("x", 1.0))));
		assertFalse(r.valid());
		// 元素 1 缺 label → struct 缺字段（逐元素递归校验，而非仅判断 Map）
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("struct 缺字段") && e.contains("param=points")));
	}

	@Test
	void arrayOfStruct_elementTypeError_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setCells,
				params("cells", List.of(1.0), "points", List.of(Map.of("x", "abc", "label", "ok"))));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("param=points")));
	}

	@Test
	void arrayOfStruct_valid_shouldPass() {
		var r = ThingModelServiceValidator.validateParams(setCells,
				params("cells", List.of("1.5"), "points", List.of(Map.of("x", 1.0, "label", "ok"))));
		assertTrue(r.valid());
	}

	@Test
	void structMissingField_shouldFail() {
		var r = ThingModelServiceValidator.validateParams(setEnv, params("env", Map.of("note", "x")));
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("struct 缺字段") && e.contains("param=env")));
	}

	@Test
	void structValid_shouldPass() {
		var r = ThingModelServiceValidator.validateParams(setEnv, params("env", Map.of("temp", "25.5", "note", "ok")));
		assertTrue(r.valid());
	}

	@Test
	void nullParams_shouldBehaveAsEmpty() {
		// required 参数缺失仍会被检出；无未定义参数错误
		var r = ThingModelServiceValidator.validateParams(setPower, null);
		assertFalse(r.valid());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("required 参数缺失")));
	}

}
