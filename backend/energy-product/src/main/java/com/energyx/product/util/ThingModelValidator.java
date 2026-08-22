package com.energyx.product.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 物模型 JSON Schema 发布校验（对齐阿里云物模型结构）。
 *
 * <p>
 * 校验目标：可解析的 JSON 对象，且 properties/services/events（若存在）为数组； 深度标准化由 access 服务的
 * {@code ThingModelParser} 在上行链路执行。本类为<b>发布入口深度校验</b>（M1）：除结构外，还校验 identifier
 * 非空且唯一、dataType 合法、specs 格式与取值（min&lt;=max、step&gt;0、elementType 合法、 structFields
 * 递归合法、enumValues 格式合法）、Service 入参与 Event 定义合法。
 * </p>
 *
 * <p>
 * <b>兼容性</b>：只校验「本次发布提交的 schema」，不修改/迁移历史数据库中的已有 schema；缺少 specs 的历史写法不受影响。
 * </p>
 */
public final class ThingModelValidator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final List<String> ARRAY_FIELDS = List.of("properties", "services", "events");

	/** 合法 dataType（与 access ModelValidator.coerce 识别集合对齐，另含 void 兼容服务侧声明） */
	private static final Set<String> LEGAL_DATA_TYPES = Set.of("int", "integer", "long", "byte", "short", "float",
			"double", "decimal", "number", "bool", "boolean", "enum", "string", "text", "date", "time", "struct",
			"array", "void");

	/** 合法访问模式 */
	private static final Set<String> LEGAL_ACCESS_MODES = Set.of("r", "w", "rw");

	/** 合法事件类型（大小写不敏感，与 {@code EventSeverity.of} 归一一致） */
	private static final Set<String> LEGAL_EVENT_TYPES = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

	/** 合法 specs 键（未知键忽略，向前兼容） */
	private static final Set<String> LEGAL_SPECS_KEYS = Set.of("min", "max", "step", "length", "elementType", "size",
			"structFields", "enumValues", "unit");

	/** identifier 必须为非空且为 TDengine 安全列名（字母/下划线开头，仅字母数字下划线） */
	private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private ThingModelValidator() {
	}

	/**
	 * 发布深度校验。
	 * @param schemaJson 物模型 JSON 文本（properties/services/events 数组结构）
	 * @throws BusinessException 结构/字段/规格任一非法时抛出（PARAM_INVALID）
	 */
	public static void validate(String schemaJson) {
		if (schemaJson == null || schemaJson.isBlank()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 不能为空");
		}
		final JsonNode root;
		try {
			root = MAPPER.readTree(schemaJson);
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 解析失败：" + e.getMessage());
		}
		if (!root.isObject()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型 JSON 必须是对象");
		}
		for (String field : ARRAY_FIELDS) {
			JsonNode node = root.get(field);
			if (node != null && !node.isArray()) {
				throw new BusinessException(ErrorCode.PARAM_INVALID, "物模型字段 " + field + " 必须是数组");
			}
		}
		validateProperties(root.path("properties"));
		validateServices(root.path("services"));
		validateEvents(root.path("events"));
	}

	/** 校验属性数组：identifier 非空唯一、dataType/specs/enumValues 合法 */
	private static void validateProperties(JsonNode properties) {
		if (!properties.isArray()) {
			return;
		}
		Set<String> identifiers = new HashSet<>();
		for (JsonNode p : properties) {
			String identifier = requireIdentifier(p, "属性", identifiers);
			validateDataType(p, identifier);
			validateCommonFields(p, identifier);
			JsonNode specs = requireSpecsObject(p, identifier);
			if (specs != null) {
				validateSpecs(specs, identifier);
			}
			validateEnumValues(p, specs, identifier);
		}
	}

	/** 校验服务数组：identifier 非空唯一、input/output 参数合法 */
	private static void validateServices(JsonNode services) {
		if (!services.isArray()) {
			return;
		}
		Set<String> identifiers = new HashSet<>();
		for (JsonNode s : services) {
			String identifier = requireIdentifier(s, "服务", identifiers);
			validateParams(s.path("input"), identifier + ".input");
			validateParams(s.path("output"), identifier + ".output");
		}
	}

	/** 校验事件数组：identifier 非空唯一、type 合法、data 参数合法 */
	private static void validateEvents(JsonNode events) {
		if (!events.isArray()) {
			return;
		}
		Set<String> identifiers = new HashSet<>();
		for (JsonNode e : events) {
			String identifier = requireIdentifier(e, "事件", identifiers);
			JsonNode type = e.path("type");
			if (!type.isMissingNode() && !type.isNull()) {
				if (!type.isTextual() || !LEGAL_EVENT_TYPES.contains(type.asText().toUpperCase())) {
					throw new BusinessException(ErrorCode.PARAM_INVALID,
							"事件 type 非法 identifier=" + identifier + " type=" + type);
				}
			}
			validateParams(e.path("data"), identifier + ".data");
		}
	}

	/** 校验参数数组（服务入参/出参、事件 data、struct 字段递归） */
	private static void validateParams(JsonNode params, String path) {
		if (!params.isArray()) {
			return;
		}
		Set<String> identifiers = new HashSet<>();
		for (JsonNode param : params) {
			String identifier = requireIdentifier(param, "参数[" + path + "]", identifiers);
			validateDataType(param, path + "." + identifier);
			JsonNode specs = requireSpecsObject(param, path + "." + identifier);
			if (specs != null) {
				validateSpecs(specs, path + "." + identifier);
			}
		}
	}

	/** specs 存在时必须是对象（缺失/显式 null 视为未定义） */
	private static JsonNode requireSpecsObject(JsonNode node, String path) {
		JsonNode specs = node.path("specs");
		if (specs.isMissingNode() || specs.isNull()) {
			return null;
		}
		if (!specs.isObject()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "specs 必须为对象：" + path);
		}
		return specs;
	}

	/** identifier 非空 + 安全字符 + 组内唯一 */
	private static String requireIdentifier(JsonNode node, String kind, Set<String> identifiers) {
		String identifier = node.path("identifier").asText(null);
		if (identifier == null || identifier.isBlank()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, kind + " identifier 不能为空");
		}
		if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID,
					kind + " identifier 非法（须字母/下划线开头，仅字母数字下划线）identifier=" + identifier);
		}
		if (!identifiers.add(identifier)) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, kind + " identifier 重复：" + identifier);
		}
		return identifier;
	}

	/** dataType 必须为合法取值 */
	private static void validateDataType(JsonNode node, String path) {
		JsonNode dataType = node.path("dataType");
		if (dataType.isMissingNode() || dataType.isNull()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 dataType：" + path);
		}
		if (!dataType.isTextual() || !LEGAL_DATA_TYPES.contains(dataType.asText())) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "非法 dataType=" + dataType + "：" + path);
		}
	}

	/** 属性公共字段：name/unit 字符串、accessMode 合法、required 布尔 */
	private static void validateCommonFields(JsonNode p, String identifier) {
		checkTextualIfPresent(p, "name", identifier);
		checkTextualIfPresent(p, "unit", identifier);
		JsonNode accessMode = p.path("accessMode");
		if (!accessMode.isMissingNode() && !accessMode.isNull()) {
			if (!accessMode.isTextual() || !LEGAL_ACCESS_MODES.contains(accessMode.asText())) {
				throw new BusinessException(ErrorCode.PARAM_INVALID,
						"属性 accessMode 非法 identifier=" + identifier + " accessMode=" + accessMode);
			}
		}
		JsonNode required = p.path("required");
		if (!required.isMissingNode() && !required.isNull() && !required.isBoolean()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "属性 required 必须为布尔 identifier=" + identifier);
		}
	}

	private static void checkTextualIfPresent(JsonNode node, String field, String identifier) {
		JsonNode value = node.path(field);
		if (!value.isMissingNode() && !value.isNull() && !value.isTextual()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "属性 " + field + " 必须为字符串 identifier=" + identifier);
		}
	}

	/**
	 * specs 深度校验：键值类型、min<=max、step>0、length/size
	 * 非负整数、elementType/structFields/enumValues 递归合法
	 */
	private static void validateSpecs(JsonNode specs, String path) {
		specs.fieldNames().forEachRemaining(name -> {
			if (!LEGAL_SPECS_KEYS.contains(name)) {
				// 未知 specs 键忽略（向前兼容），不拒绝
				return;
			}
			JsonNode v = specs.get(name);
			// 用 if/else 链而非 switch：spring-javaformat:apply 会破坏新版多标签 switch，导致编译失败
			if ("min".equals(name) || "max".equals(name) || "step".equals(name)) {
				if (!v.isNumber()) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs." + name + " 必须为数值：" + path);
				}
				if ("step".equals(name) && v.asDouble() <= 0) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs.step 必须大于 0：" + path);
				}
			}
			else if ("length".equals(name) || "size".equals(name)) {
				if (!v.isNumber() || v.asDouble() < 0 || v.asDouble() != Math.rint(v.asDouble())) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs." + name + " 必须为非负整数：" + path);
				}
			}
			else if ("elementType".equals(name)) {
				if (!v.isTextual() || !LEGAL_DATA_TYPES.contains(v.asText())) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs.elementType 非法（须为合法 dataType）：" + path);
				}
			}
			else if ("structFields".equals(name)) {
				if (!v.isArray()) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs.structFields 必须为数组：" + path);
				}
				validateParams(v, path + ".structFields");
			}
			else if ("enumValues".equals(name)) {
				validateEnumValuesArray(v, path);
			}
			else if ("unit".equals(name)) {
				if (!v.isTextual()) {
					throw new BusinessException(ErrorCode.PARAM_INVALID, "specs.unit 必须为字符串：" + path);
				}
			}
		});
		// min <= max
		JsonNode min = specs.get("min");
		JsonNode max = specs.get("max");
		if (min != null && min.isNumber() && max != null && max.isNumber() && min.asDouble() > max.asDouble()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID,
					"specs.min 不能大于 specs.max：" + path + " min=" + min + " max=" + max);
		}
	}

	/**
	 * enumValues 契约校验（顶层与 specs.enumValues 双形式兼容）：
	 * <ul>
	 * <li>顶层存在且 specs.enumValues 也存在且内容不一致 → 拒绝（发布入口显式暴露冲突，不静默覆盖）；</li>
	 * <li>仅其一存在 → 按其校验格式。</li>
	 * </ul>
	 */
	private static void validateEnumValues(JsonNode p, JsonNode specs, String identifier) {
		JsonNode top = p.path("enumValues");
		boolean topMissing = top.isMissingNode() || top.isNull();
		JsonNode specsEnums = specs != null && specs.isObject() ? specs.path("enumValues") : null;
		boolean specsMissing = specsEnums == null || specsEnums.isMissingNode() || specsEnums.isNull();
		// 存在但非数组 → 显式拒绝（不静默跳过）
		if (!topMissing && !top.isArray()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID,
					"enumValues 必须为数组 identifier=" + identifier + " enumValues=" + top);
		}
		if (!specsMissing && !specsEnums.isArray()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID,
					"specs.enumValues 必须为数组 identifier=" + identifier + " enumValues=" + specsEnums);
		}
		boolean topPresent = top.isArray();
		boolean specsPresent = specsEnums != null && specsEnums.isArray();
		if (topPresent && specsPresent && !top.equals(specsEnums)) {
			throw new BusinessException(ErrorCode.PARAM_INVALID,
					"enumValues 冲突：顶层与 specs.enumValues 不一致 identifier=" + identifier);
		}
		if (topPresent) {
			validateEnumValuesArray(top, identifier);
		}
		else if (specsPresent) {
			validateEnumValuesArray(specsEnums, identifier);
		}
	}

	/** enumValues 格式：数组，每项为对象且含 value（desc 可选字符串） */
	private static void validateEnumValuesArray(JsonNode enums, String path) {
		if (!enums.isArray()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "enumValues 必须为数组：" + path);
		}
		for (JsonNode item : enums) {
			if (!item.isObject() || item.get("value") == null || item.get("value").isNull()) {
				throw new BusinessException(ErrorCode.PARAM_INVALID,
						"enumValues 项必须为含 value 的对象：" + path + " item=" + item);
			}
			JsonNode desc = item.get("desc");
			if (desc != null && !desc.isNull() && !desc.isTextual()) {
				throw new BusinessException(ErrorCode.PARAM_INVALID,
						"enumValues 项 desc 必须为字符串：" + path + " item=" + item);
			}
		}
	}

}
