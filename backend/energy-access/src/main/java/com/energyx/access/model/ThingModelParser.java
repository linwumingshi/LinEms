package com.energyx.access.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 物模型 schema_json 解析器（纯函数，无 I/O）。
 *
 * <p>输入为 iot_thing_model.schema_json（对齐阿里云物模型结构），输出 {@link ThingModel}；
 * 解析失败抛异常，由缓存层捕获并降级（拒绝该产品的上报标准化，保障链路不挂）。</p>
 */
public final class ThingModelParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ThingModelParser() {
    }

    public static ThingModel parse(String schemaJson) throws Exception {
        JsonNode root = MAPPER.readTree(schemaJson);
        ThingModel model = new ThingModel();
        model.setVersion(root.path("version").asText(null));

        JsonNode properties = root.path("properties");
        if (properties.isArray()) {
            for (JsonNode p : properties) {
                ThingModelProperty prop = parseProperty(p);
                model.getProperties().put(prop.getIdentifier(), prop);
            }
        }
        JsonNode services = root.path("services");
        if (services.isArray()) {
            for (JsonNode s : services) {
                ThingModelService svc = parseService(s);
                model.getServices().put(svc.getIdentifier(), svc);
            }
        }
        JsonNode events = root.path("events");
        if (events.isArray()) {
            for (JsonNode e : events) {
                ThingModelEvent ev = parseEvent(e);
                model.getEvents().put(ev.getIdentifier(), ev);
            }
        }
        return model;
    }

    private static ThingModelProperty parseProperty(JsonNode p) {
        ThingModelProperty prop = new ThingModelProperty();
        prop.setIdentifier(p.path("identifier").asText());
        prop.setName(p.path("name").asText());
        prop.setDataType(p.path("dataType").asText());
        prop.setUnit(p.path("unit").asText(null));
        prop.setAccessMode(p.path("accessMode").asText("r"));
        prop.setRequired(p.path("required").asBoolean(false));
        JsonNode enums = p.path("enumValues");
        if (enums.isArray() && !enums.isEmpty()) {
            List<EnumValue> list = new ArrayList<>();
            for (JsonNode ev : enums) {
                EnumValue v = new EnumValue();
                v.setValue(MAPPER.convertValue(ev.get("value"), Object.class));
                v.setDesc(ev.path("desc").asText(null));
                list.add(v);
            }
            prop.setEnumValues(list);
        }
        return prop;
    }

    private static ThingModelService parseService(JsonNode s) {
        ThingModelService svc = new ThingModelService();
        svc.setIdentifier(s.path("identifier").asText());
        svc.setName(s.path("name").asText());
        JsonNode input = s.path("input");
        if (input.isArray() && !input.isEmpty()) {
            List<ThingModelParam> params = new ArrayList<>();
            for (JsonNode in : input) {
                ThingModelParam param = new ThingModelParam();
                param.setIdentifier(in.path("identifier").asText());
                param.setDataType(in.path("dataType").asText());
                param.setUnit(in.path("unit").asText(null));
                params.add(param);
            }
            svc.setInput(params);
        }
        return svc;
    }

    private static ThingModelEvent parseEvent(JsonNode e) {
        ThingModelEvent ev = new ThingModelEvent();
        ev.setIdentifier(e.path("identifier").asText());
        ev.setName(e.path("name").asText());
        ev.setType(e.path("type").asText("WARN"));
        return ev;
    }
}
