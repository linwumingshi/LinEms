package com.energyx.common.thingmodel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物模型 desired 校验器测试（M2.2）。
 *
 * <p>
 * 覆盖：rw/w 合法、r 拒绝、未定义属性拒绝、enum/min-max/length/struct/array/array-of-struct 深度校验、
 * 多属性原子语义、required 不强制、null 语义。
 * </p>
 */
class ThingModelDesiredValidatorTest {

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

	private ThingModel model;

	@BeforeEach
	void setUp() throws Exception {
		model = ThingModelParser.parse(SCHEMA);
	}

	private static Map<String, Object> desired(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	private static boolean hasError(ThingModelDesiredValidator.DesiredValidationResult r, String property,
			String keyword) {
		return r.errors().stream().anyMatch(e -> e.contains("property=" + property) && e.contains(keyword));
	}

	@Test
	void rwProperty_valid_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("power", 500));
		assertTrue(r.valid());
	}

	@Test
	void wProperty_valid_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("target", "ok"));
		assertTrue(r.valid());
	}

	@Test
	void rProperty_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("soc", 50));
		assertFalse(r.valid());
		assertTrue(hasError(r, "soc", "只读属性不可写"));
	}

	@Test
	void undefinedProperty_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("ghost", 1));
		assertFalse(r.valid());
		assertTrue(hasError(r, "ghost", "属性不存在"));
	}

	@Test
	void enumError_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("mode", 99));
		assertFalse(r.valid());
		assertTrue(hasError(r, "mode", "enum 取值越界"));
	}

	@Test
	void minMaxOutOfRange_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("power", 12000));
		assertFalse(r.valid());
		assertTrue(hasError(r, "power", "大于 max"));
	}

	@Test
	void stringOverLength_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("target", "0123456789ABCDEFG"));
		assertFalse(r.valid());
		assertTrue(hasError(r, "target", "字符串超长"));
	}

	@Test
	void structMissingField_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("env", Map.of("note", "x")));
		assertFalse(r.valid());
		assertTrue(hasError(r, "env", "struct 缺字段"));
	}

	@Test
	void structValid_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("env", Map.of("temp", "25.5", "note", "ok")));
		assertTrue(r.valid());
	}

	@Test
	void arrayTypeError_shouldReject() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("points", "not-array"));
		assertFalse(r.valid());
		assertTrue(hasError(r, "points", "array 类型需数组"));
	}

	@Test
	void arrayOfStructFieldError_shouldReject() {
		// points[0] 缺 label → 逐元素 struct 递归拒绝（M1/M2.1 语义，非仅判断 Map）
		var r = ThingModelDesiredValidator.validateDesired(model, desired("points", List.of(Map.of("x", 1.0))));
		assertFalse(r.valid());
		assertTrue(hasError(r, "points", "struct 缺字段"));
	}

	@Test
	void arrayOfStructValid_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model,
				desired("points", List.of(Map.of("x", 1.0, "label", "ok"))));
		assertTrue(r.valid());
	}

	@Test
	void multiProperty_oneInvalid_shouldBeOverallInvalid() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("power", 100, "soc", 50));
		assertFalse(r.valid());
		assertTrue(hasError(r, "soc", "只读属性不可写"));
	}

	@Test
	void multiProperty_allValid_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model, desired("power", 100, "target", "ok", "mode", 1));
		assertTrue(r.valid());
	}

	@Test
	void requiredNotPresentInDesired_shouldNotFail() {
		// desired 部分更新语义：required 不在此强制，未出现的 required 属性不得报错
		ThingModel m;
		try {
			m = ThingModelParser.parse("""
					{"properties":[{"identifier":"a","dataType":"float","accessMode":"rw","required":true},
					               {"identifier":"b","dataType":"float","accessMode":"rw"}]}
					""");
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		var r = ThingModelDesiredValidator.validateDesired(m, desired("b", 1));
		assertTrue(r.valid());
	}

	@Test
	void nullValue_shouldFollowCoerceSemantics() {
		// null 值合法（ModelValidator.coerce(null)=null），不定义第二套 null 规则
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("power", null);
		var r = ThingModelDesiredValidator.validateDesired(model, d);
		assertTrue(r.valid());
	}

	@Test
	void emptyDesired_shouldPass() {
		var r = ThingModelDesiredValidator.validateDesired(model, Map.of());
		assertTrue(r.valid());
		assertTrue(r.errors().isEmpty());
	}

}
