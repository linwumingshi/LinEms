package com.energyx.common.thingmodel;

import com.energyx.common.enums.EventSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 物模型 schema_json 解析器（纯函数，无 I/O）。M2.1 起迁入 energy-common，access 与 command 共享。
 *
 * <p>
 * 输入为 iot_thing_model.schema_json（对齐阿里云物模型结构），输出 {@link ThingModel}；
 * 解析失败抛异常，由调用方（缓存层/校验层）捕获并降级。
 * </p>
 *
 * <p>
 * <b>enumValues 契约（M1）</b>：兼容两种写法——顶层 {@code enumValues}（历史种子数据）与
 * {@code specs.enumValues}（前端 TSL 约定）；解析统一为单一 {@code enumValues} 列表，优先级 <b>顶层 &gt;
 * specs</b>。两者同时存在且内容不一致时，取顶层并记录告警（不静默覆盖）。 属性与服务参数（{@link ThingModelParam}，M2.1）均适用该契约。
 * </p>
 */
public final class ThingModelParser {

	private static final Logger log = LoggerFactory.getLogger(ThingModelParser.class);

	/** 共享 ObjectMapper（只读解析，无状态线程安全） */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 工具类禁止实例化 */
	private ThingModelParser() {
	}

	/**
	 * 解析物模型 schema_json 为内存对象。
	 * @param schemaJson iot_thing_model.schema_json 文本（对齐阿里云物模型结构）
	 * @return 按 identifier 索引的 {@link ThingModel}（properties/services/events 三类元素）
	 * @throws Exception JSON 解析失败 / 结构非法时抛出，由调用方捕获降级
	 */
	public static ThingModel parse(String schemaJson) throws Exception {
		JsonNode root = MAPPER.readTree(schemaJson);
		ThingModel model = new ThingModel();
		model.setVersion(root.path("version").asText(null));

		// 1. 解析属性 properties[]，按 identifier 建索引
		JsonNode properties = root.path("properties");
		if (properties.isArray()) {
			for (JsonNode p : properties) {
				ThingModelProperty prop = parseProperty(p);
				model.getProperties().put(prop.getIdentifier(), prop);
			}
		}
		// 2. 解析服务 services[]（可下发指令），按 identifier 建索引
		JsonNode services = root.path("services");
		if (services.isArray()) {
			for (JsonNode s : services) {
				ThingModelService svc = parseService(s);
				model.getServices().put(svc.getIdentifier(), svc);
			}
		}
		// 3. 解析事件 events[]，按 identifier 建索引
		JsonNode events = root.path("events");
		if (events.isArray()) {
			for (JsonNode e : events) {
				ThingModelEvent ev = parseEvent(e);
				model.getEvents().put(ev.getIdentifier(), ev);
			}
		}
		return model;
	}

	/**
	 * 解析单个属性节点：抽取 identifier/name/dataType/unit/accessMode/required 与 specs 深度校验字段， 并将
	 * enumValues（顶层优先，specs.enumValues 兜底）转换为 {@link EnumValue} 列表。
	 * @param p 属性 JSON 节点
	 * @return 解析后的 {@link ThingModelProperty}
	 */
	private static ThingModelProperty parseProperty(JsonNode p) {
		ThingModelProperty prop = new ThingModelProperty();
		prop.setIdentifier(p.path("identifier").asText());
		prop.setName(p.path("name").asText());
		prop.setDataType(p.path("dataType").asText());
		prop.setUnit(p.path("unit").asText(null));
		prop.setAccessMode(p.path("accessMode").asText("r"));
		prop.setRequired(p.path("required").asBoolean(false));

		JsonNode specs = p.path("specs");
		if (specs.isObject()) {
			parseSpecs(specs, prop);
		}

		// enumValues 契约：顶层优先，specs.enumValues 兜底；两者同时存在且不一致 → 取顶层并告警（不静默覆盖）
		prop.setEnumValues(resolveEnumValues(p, specs, prop.getIdentifier()));
		return prop;
	}

	/**
	 * 解析单个服务（可下发指令）节点：抽取 identifier/name 与 input 入参列表（含 specs/required/enumValues）。
	 * @param s 服务 JSON 节点
	 * @return 解析后的 {@link ThingModelService}
	 */
	private static ThingModelService parseService(JsonNode s) {
		ThingModelService svc = new ThingModelService();
		svc.setIdentifier(s.path("identifier").asText());
		svc.setName(s.path("name").asText());
		JsonNode input = s.path("input");
		if (input.isArray() && !input.isEmpty()) {
			List<ThingModelParam> params = new ArrayList<>();
			for (JsonNode in : input) {
				params.add(parseParam(in));
			}
			svc.setInput(params);
		}
		return svc;
	}

	/**
	 * 解析单个事件节点：抽取 identifier/name，并将 type 映射为 {@link EventSeverity} （缺省按 WARN 处理，对应
	 * TDengine 严重级别 2）。
	 * @param e 事件 JSON 节点
	 * @return 解析后的 {@link ThingModelEvent}
	 */
	private static ThingModelEvent parseEvent(JsonNode e) {
		ThingModelEvent ev = new ThingModelEvent();
		ev.setIdentifier(e.path("identifier").asText());
		ev.setName(e.path("name").asText());
		// type 缺省 WARN：设备未显式声明级别时按"一般"(TDengine 严重级别 2)处理
		ev.setType(EventSeverity.of(e.path("type").asText("WARN")));
		return ev;
	}

	/**
	 * 解析单个参数节点（服务入参/出参、struct 字段）：identifier/dataType/unit/required + specs 深度校验字段 +
	 * enumValues（M2.1，契约同属性）。
	 * @param node 参数 JSON 节点
	 * @return 解析后的 {@link ThingModelParam}
	 */
	private static ThingModelParam parseParam(JsonNode node) {
		ThingModelParam param = new ThingModelParam();
		param.setIdentifier(node.path("identifier").asText());
		param.setDataType(node.path("dataType").asText());
		param.setUnit(node.path("unit").asText(null));
		param.setRequired(node.path("required").asBoolean(false));
		JsonNode specs = node.path("specs");
		if (specs.isObject()) {
			parseSpecs(specs, param);
		}
		param.setEnumValues(resolveEnumValues(node, specs, param.getIdentifier()));
		return param;
	}

	/** 解析属性 specs 对象：min/max/step/length/elementType/size/structFields（非法值忽略，发布校验兜底） */
	private static void parseSpecs(JsonNode specs, ThingModelProperty prop) {
		if (specs.path("min").isNumber()) {
			prop.setMin(specs.path("min").asDouble());
		}
		if (specs.path("max").isNumber()) {
			prop.setMax(specs.path("max").asDouble());
		}
		if (specs.path("step").isNumber()) {
			prop.setStep(specs.path("step").asDouble());
		}
		prop.setLength(intSpec(specs, "length"));
		if (specs.path("elementType").isTextual()) {
			prop.setElementType(specs.path("elementType").asText());
		}
		prop.setSize(intSpec(specs, "size"));
		prop.setStructFields(parseStructFields(specs));
	}

	/** 解析参数 specs 对象：min/max/length/elementType/size/structFields（非法值忽略，发布校验兜底） */
	private static void parseSpecs(JsonNode specs, ThingModelParam param) {
		if (specs.path("min").isNumber()) {
			param.setMin(specs.path("min").asDouble());
		}
		if (specs.path("max").isNumber()) {
			param.setMax(specs.path("max").asDouble());
		}
		param.setLength(intSpec(specs, "length"));
		if (specs.path("elementType").isTextual()) {
			param.setElementType(specs.path("elementType").asText());
		}
		param.setSize(intSpec(specs, "size"));
		param.setStructFields(parseStructFields(specs));
	}

	/** 解析 specs.structFields：struct 字段定义数组 → {@link ThingModelParam} 列表（非法项忽略） */
	private static List<ThingModelParam> parseStructFields(JsonNode specs) {
		JsonNode fields = specs.path("structFields");
		if (!fields.isArray() || fields.isEmpty()) {
			return null;
		}
		List<ThingModelParam> list = new ArrayList<>();
		for (JsonNode f : fields) {
			if (f.isObject()) {
				list.add(parseParam(f));
			}
		}
		return list.isEmpty() ? null : list;
	}

	/**
	 * enumValues 双形式解析（属性/参数通用）：顶层优先、specs.enumValues 兜底； 两者同时存在且不一致 → 取顶层并记录告警（不静默覆盖）。
	 */
	private static List<EnumValue> resolveEnumValues(JsonNode node, JsonNode specs, String identifier) {
		JsonNode topEnums = node.path("enumValues");
		JsonNode specsEnums = specs.isObject() ? specs.path("enumValues") : null;
		boolean topValid = topEnums.isArray() && !topEnums.isEmpty();
		boolean specsValid = specsEnums != null && specsEnums.isArray() && !specsEnums.isEmpty();
		if (topValid) {
			if (specsValid && !topEnums.equals(specsEnums)) {
				log.warn("[ThingModel] enumValues 冲突：顶层与 specs.enumValues 不一致，按顶层优先 identifier={}", identifier);
			}
			return parseEnumValues(topEnums);
		}
		if (specsValid) {
			return parseEnumValues(specsEnums);
		}
		return null;
	}

	/** 整数型 specs 值（length/size）：仅接受非负整数，其余返回 null（运行时宽容，发布校验兜底） */
	private static Integer intSpec(JsonNode specs, String key) {
		JsonNode n = specs.path(key);
		if (!n.isNumber()) {
			return null;
		}
		double d = n.asDouble();
		if (d < 0 || d != Math.rint(d)) {
			return null;
		}
		return (int) d;
	}

	/** 解析 enumValues 数组为 {@link EnumValue} 列表（数值/字符串统一保留原值） */
	private static List<EnumValue> parseEnumValues(JsonNode enums) {
		List<EnumValue> list = new ArrayList<>();
		for (JsonNode ev : enums) {
			EnumValue v = new EnumValue();
			v.setValue(MAPPER.convertValue(ev.get("value"), Object.class));
			v.setDesc(ev.path("desc").asText(null));
			list.add(v);
		}
		return list;
	}

}
