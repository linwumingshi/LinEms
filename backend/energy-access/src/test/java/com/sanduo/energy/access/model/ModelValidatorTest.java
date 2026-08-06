package com.sanduo.energy.access.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物模型上报校验 + 类型强转测试。
 */
class ModelValidatorTest {

    private ThingModel model;

    @BeforeEach
    void setUp() throws Exception {
        model = ThingModelParser.parse("""
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
        reported.put("ghost", 42);   // 未在物模型登记

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
        ModelValidator.ValidationResult result =
                ModelValidator.validateProperties(model, new LinkedHashMap<>());
        assertTrue(result.valid());
        assertTrue(result.coerced().isEmpty());
    }

    @Test
    void checkEvent_shouldMapSeverity() {
        assertNotNull(ModelValidator.checkEvent(model, "overTemp"));
        assertEquals(2, ModelValidator.checkEvent(model, "overTemp").severity());   // WARN→2
        assertEquals(3, ModelValidator.checkEvent(model, "bmsFault").severity());   // ERROR→3
        assertNull(ModelValidator.checkEvent(model, "notExists"));
    }

    @Test
    void severityOf_shouldMapAllLevels() {
        assertEquals(1, ModelValidator.severityOf("INFO"));
        assertEquals(2, ModelValidator.severityOf("WARN"));
        assertEquals(3, ModelValidator.severityOf("ERROR"));
        assertEquals(4, ModelValidator.severityOf("CRITICAL"));
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
}
