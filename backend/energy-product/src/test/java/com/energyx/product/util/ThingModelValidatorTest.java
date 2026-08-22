package com.energyx.product.util;

import com.energyx.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物模型发布深度校验测试（M1）。
 */
class ThingModelValidatorTest {

	private void assertInvalid(String schema, String keyword) {
		BusinessException ex = assertThrows(BusinessException.class, () -> ThingModelValidator.validate(schema));
		assertTrue(ex.getMessage().contains(keyword), "期望错误包含[" + keyword + "]，实际：" + ex.getMessage());
	}

	private static final String VALID_SCHEMA = """
			{"properties":[
			   {"identifier":"soc","name":"荷电状态","dataType":"float","unit":"%","accessMode":"r",
			    "specs":{"min":0,"max":100,"step":0.5}},
			   {"identifier":"runMode","name":"运行模式","dataType":"enum","accessMode":"rw",
			    "enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}]},
			   {"identifier":"env","name":"环境量","dataType":"struct",
			    "specs":{"structFields":[{"identifier":"temp","dataType":"float","specs":{"min":-40,"max":120}}]}},
			   {"identifier":"cells","name":"电芯电压","dataType":"array","specs":{"elementType":"float","size":512}}
			 ],
			 "services":[
			   {"identifier":"setPower","name":"调整功率",
			    "input":[{"identifier":"power","dataType":"float","specs":{"min":0,"max":1000}}]}
			 ],
			 "events":[
			   {"identifier":"overTemp","name":"过温告警","type":"WARN","data":[{"identifier":"temp","dataType":"float"}]}
			 ]}
			""";

	@Test
	void validSchema_shouldPass() {
		assertDoesNotThrow(() -> ThingModelValidator.validate(VALID_SCHEMA));
	}

	@Test
	void validSchemaWithSpecsEnumValues_shouldPass() {
		// 形式 B：specs.enumValues 为唯一来源时合法
		assertDoesNotThrow(() -> ThingModelValidator.validate("""
				{"properties":[{"identifier":"runMode","dataType":"enum",
				  "specs":{"enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"}]}}]}
				"""));
	}

	@Test
	void validSchemaBothEnumValuesIdentical_shouldPass() {
		// 顶层与 specs.enumValues 同时存在且一致 → 合法（冗余但无冲突）
		assertDoesNotThrow(() -> ThingModelValidator.validate("""
				{"properties":[{"identifier":"runMode","dataType":"enum",
				  "enumValues":[{"value":0},{"value":1}],
				  "specs":{"enumValues":[{"value":0},{"value":1}]}}]}
				"""));
	}

	@Test
	void emptyJson_shouldReject() {
		assertInvalid(null, "不能为空");
		assertInvalid("", "不能为空");
		assertInvalid("not-json{", "解析失败");
		assertInvalid("\"str\"", "必须是对象");
	}

	@Test
	void duplicateIdentifier_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float"},{"identifier":"soc","dataType":"float"}]}
				""", "identifier 重复");
	}

	@Test
	void blankIdentifier_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":" ","dataType":"float"}]}
				""", "identifier 不能为空");
	}

	@Test
	void unsafeIdentifier_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"1abc","dataType":"float"}]}
				""", "identifier 非法");
	}

	@Test
	void illegalDataType_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"x","dataType":"color"}]}
				""", "非法 dataType");
		assertInvalid("""
				{"properties":[{"identifier":"x"}]}
				""", "缺少 dataType");
	}

	@Test
	void illegalSpecsValueType_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"min":"abc"}}]}
				""", "specs.min 必须为数值");
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"length":1.5}}]}
				""", "specs.length 必须为非负整数");
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"size":-1}}]}
				""", "specs.size 必须为非负整数");
		// specs 存在但非对象
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":"x"}]}
				""", "specs 必须为对象");
		assertInvalid("""
				{"services":[{"identifier":"setPower","input":[{"identifier":"p","dataType":"float","specs":5}]}]}
				""", "specs 必须为对象");
	}

	@Test
	void minGreaterThanMax_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"min":100,"max":0}}]}
				""", "min 不能大于");
	}

	@Test
	void nonPositiveStep_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"min":0,"step":0}}]}
				""", "step 必须大于 0");
		assertInvalid("""
				{"properties":[{"identifier":"soc","dataType":"float","specs":{"min":0,"step":-1}}]}
				""", "step 必须大于 0");
	}

	@Test
	void illegalElementType_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"cells","dataType":"array","specs":{"elementType":"color"}}]}
				""", "elementType 非法");
	}

	@Test
	void illegalStructFields_shouldReject() {
		// structFields 非数组
		assertInvalid("""
				{"properties":[{"identifier":"env","dataType":"struct","specs":{"structFields":"x"}}]}
				""", "structFields 必须为数组");
		// structFields 内字段缺 identifier
		assertInvalid(
				"""
						{"properties":[{"identifier":"env","dataType":"struct","specs":{"structFields":[{"dataType":"float"}]}}]}
						""",
				"identifier 不能为空");
		// structFields 内字段非法 dataType
		assertInvalid(
				"""
						{"properties":[{"identifier":"env","dataType":"struct","specs":{"structFields":[{"identifier":"t","dataType":"color"}]}}]}
						""",
				"非法 dataType");
		// structFields 内字段重复 identifier
		assertInvalid("""
				{"properties":[{"identifier":"env","dataType":"struct","specs":{"structFields":[
				  {"identifier":"t","dataType":"float"},{"identifier":"t","dataType":"float"}]}}]}
				""", "identifier 重复");
	}

	@Test
	void illegalEnumValues_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"runMode","dataType":"enum","enumValues":"x"}]}
				""", "enumValues 必须为数组");
		assertInvalid("""
				{"properties":[{"identifier":"runMode","dataType":"enum","enumValues":[{"desc":"缺value"}]}]}
				""", "必须为含 value 的对象");
		assertInvalid("""
				{"properties":[{"identifier":"runMode","dataType":"enum","enumValues":[{"value":0,"desc":1}]}]}
				""", "desc 必须为字符串");
	}

	@Test
	void conflictingEnumValues_shouldReject() {
		// 顶层与 specs.enumValues 同时存在且不一致 → 发布入口显式拒绝，不静默覆盖
		assertInvalid("""
				{"properties":[{"identifier":"runMode","dataType":"enum",
				  "enumValues":[{"value":0}],"specs":{"enumValues":[{"value":9}]}}]}
				""", "enumValues 冲突");
	}

	@Test
	void illegalServiceParam_shouldReject() {
		// input 参数 identifier 为空
		assertInvalid("""
				{"services":[{"identifier":"setPower","input":[{"dataType":"float"}]}]}
				""", "identifier 不能为空");
		// input 参数 identifier 重复
		assertInvalid("""
				{"services":[{"identifier":"setPower","input":[{"identifier":"p","dataType":"float"},
				  {"identifier":"p","dataType":"int"}]}]}
				""", "identifier 重复");
		// input 参数非法 dataType
		assertInvalid("""
				{"services":[{"identifier":"setPower","input":[{"identifier":"p","dataType":"color"}]}]}
				""", "非法 dataType");
		// input 参数 specs 非法（min>max）
		assertInvalid(
				"""
						{"services":[{"identifier":"setPower","input":[{"identifier":"p","dataType":"float","specs":{"min":10,"max":1}}]}]}
						""",
				"min 不能大于");
	}

	@Test
	void illegalEvent_shouldReject() {
		// type 非法
		assertInvalid("""
				{"events":[{"identifier":"evt","type":"URGENT"}]}
				""", "type 非法");
		// data 参数非法
		assertInvalid("""
				{"events":[{"identifier":"evt","type":"WARN","data":[{"identifier":"t","dataType":"color"}]}]}
				""", "非法 dataType");
		// 事件 identifier 重复
		assertInvalid("""
				{"events":[{"identifier":"evt","type":"WARN"},{"identifier":"evt","type":"ERROR"}]}
				""", "identifier 重复");
	}

	@Test
	void illegalAccessMode_shouldReject() {
		assertInvalid("""
				{"properties":[{"identifier":"x","dataType":"float","accessMode":"rwx"}]}
				""", "accessMode 非法");
	}

}
