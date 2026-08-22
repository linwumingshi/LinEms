package com.energyx.access.model;

import com.energyx.common.enums.EventSeverity;
import com.energyx.common.thingmodel.EnumValue;
import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParam;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 物模型 schema_json 解析测试：结构与 20_product.sql 种子数据对齐。
 */
class ThingModelParserTest {

	/** 与 sql/mysql/20_product.sql 中 snd_ess_pcs 种子物模型一致的 JSON（缩略） */
	private static final String SEED_SCHEMA = """
			{"properties":[{"identifier":"soc","name":"荷电状态","dataType":"float","unit":"%","accessMode":"r"},
			               {"identifier":"runMode","name":"运行模式","dataType":"enum","enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}],"accessMode":"rw"}],
			 "services":[{"identifier":"setPower","name":"调整功率","input":[{"identifier":"power","dataType":"float","unit":"kW"}],"output":[]}],
			 "events":[{"identifier":"overTemp","name":"过温告警","type":"WARN","data":[]},
			           {"identifier":"bmsFault","name":"BMS故障","type":"ERROR","data":[]}]}
			""";

	@Test
	void parse_shouldExtractPropertiesServicesEvents() throws Exception {
		ThingModel model = ThingModelParser.parse(SEED_SCHEMA);

		assertEquals(2, model.getProperties().size());
		assertNotNull(model.getProperties().get("soc"));
		assertEquals("float", model.getProperties().get("soc").getDataType());
		assertEquals("rw", model.getProperties().get("runMode").getAccessMode());

		assertEquals(1, model.getServices().size());
		assertEquals("setPower", model.getServices().get("setPower").getIdentifier());
		assertEquals(1, model.getServices().get("setPower").getInput().size());

		assertEquals(2, model.getEvents().size());
		assertEquals(EventSeverity.WARN, model.getEvents().get("overTemp").getType());
		assertEquals(EventSeverity.ERROR, model.getEvents().get("bmsFault").getType());
	}

	@Test
	void parse_shouldExtractEnumValues() throws Exception {
		ThingModel model = ThingModelParser.parse(SEED_SCHEMA);
		List<EnumValue> enums = model.getProperties().get("runMode").getEnumValues();
		assertNotNull(enums);
		assertEquals(3, enums.size());
		assertEquals("放电", enums.get(2).getDesc());
		assertEquals(2, ((Number) enums.get(2).getValue()).longValue());
	}

	@Test
	void parse_shouldRejectMalformedSchema() {
		assertThrows(Exception.class, () -> ThingModelParser.parse("{not-json"));
		assertThrows(Exception.class, () -> ThingModelParser.parse(null));
	}

	@Test
	void parse_emptyModel_shouldYieldEmptyMaps() throws Exception {
		ThingModel model = ThingModelParser.parse("{}");
		assertTrue(model.getProperties().isEmpty());
		assertTrue(model.getServices().isEmpty());
		assertTrue(model.getEvents().isEmpty());
		assertNull(model.getVersion());
	}

	// ------------------------------------------------------------------
	// M1 物模型契约统一：enumValues 双形式 + specs 深度校验字段
	// ------------------------------------------------------------------

	@Test
	void parse_specsEnumValues_shouldFallbackToSpecs() throws Exception {
		// 形式 B：enumValues 位于 specs 内（前端 TSL 约定）
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"runMode","name":"运行模式","dataType":"enum",
				  "specs":{"enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}]}}]}
				""");
		List<EnumValue> enums = model.getProperties().get("runMode").getEnumValues();
		assertNotNull(enums);
		assertEquals(3, enums.size());
		assertEquals("放电", enums.get(2).getDesc());
		assertEquals(2, ((Number) enums.get(2).getValue()).longValue());
	}

	@Test
	void parse_bothEnumValues_shouldPreferTopLevel() throws Exception {
		// 两者同时存在且不一致 → 顶层优先（明确策略，不静默覆盖）
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"runMode","name":"运行模式","dataType":"enum",
				  "enumValues":[{"value":1,"desc":"顶层1"}],
				  "specs":{"enumValues":[{"value":9,"desc":"specs9"}]}}]}
				""");
		List<EnumValue> enums = model.getProperties().get("runMode").getEnumValues();
		assertNotNull(enums);
		assertEquals(1, enums.size());
		assertEquals(1, ((Number) enums.get(0).getValue()).longValue());
		assertEquals("顶层1", enums.get(0).getDesc());
	}

	@Test
	void parse_specsMinMaxStep_shouldParse() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"soc","name":"荷电状态","dataType":"float","unit":"%",
				  "specs":{"min":0,"max":100,"step":0.5}}]}
				""");
		ThingModelProperty prop = model.getProperties().get("soc");
		assertEquals(0.0, prop.getMin());
		assertEquals(100.0, prop.getMax());
		assertEquals(0.5, prop.getStep());
	}

	@Test
	void parse_specsLength_shouldParse() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"unitNo","name":"柜号","dataType":"string",
				  "specs":{"length":32}}]}
				""");
		assertEquals(32, model.getProperties().get("unitNo").getLength());
	}

	@Test
	void parse_specsStructFields_shouldParse() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"env","name":"环境量","dataType":"struct",
				  "specs":{"structFields":[
				    {"identifier":"temp","dataType":"float","specs":{"min":-40,"max":120}},
				    {"identifier":"label","dataType":"string","specs":{"length":16}}
				  ]}}]}
				""");
		List<ThingModelParam> fields = model.getProperties().get("env").getStructFields();
		assertNotNull(fields);
		assertEquals(2, fields.size());
		ThingModelParam temp = fields.get(0);
		assertEquals("temp", temp.getIdentifier());
		assertEquals(-40.0, temp.getMin());
		assertEquals(120.0, temp.getMax());
		assertEquals(16, fields.get(1).getLength());
	}

	@Test
	void parse_specsArray_shouldParse() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"properties":[{"identifier":"cells","name":"电芯电压","dataType":"array",
				  "specs":{"elementType":"float","size":512}}]}
				""");
		ThingModelProperty prop = model.getProperties().get("cells");
		assertEquals("float", prop.getElementType());
		assertEquals(512, prop.getSize());
	}

	@Test
	void parse_serviceParamSpecs_shouldParse() throws Exception {
		ThingModel model = ThingModelParser.parse("""
				{"services":[{"identifier":"setPower","name":"调整功率",
				  "input":[{"identifier":"power","dataType":"float","unit":"kW","specs":{"min":0,"max":1000}},
				           {"identifier":"mode","dataType":"enum"}]}]}
				""");
		List<ThingModelParam> input = model.getServices().get("setPower").getInput();
		assertNotNull(input);
		assertEquals(2, input.size());
		assertEquals(0.0, input.get(0).getMin());
		assertEquals(1000.0, input.get(0).getMax());
		assertEquals("enum", input.get(1).getDataType());
	}

}
