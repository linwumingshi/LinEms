package com.sanduo.energy.access.model;

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
        assertEquals("WARN", model.getEvents().get("overTemp").getType());
        assertEquals("ERROR", model.getEvents().get("bmsFault").getType());
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
}
