package com.energyx.common.thingmodel;

import com.energyx.common.enums.EventSeverity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物模型上报校验 + 类型强转（纯函数，无 I/O）。M2.1 起迁入 energy-common，供 access 上行校验与 command 下发校验共享。
 *
 * <p>
 * 策略：
 * <ul>
 * <li>未知 identifier —— 拒绝（防脏数据污染宽表/影子，白名单语义，对齐 ADR-008）；</li>
 * <li>类型强转失败 —— 拒绝该属性（如 float 收到 "abc"）；</li>
 * <li>enum 取值越界 —— 拒绝该属性；</li>
 * <li>required 缺失 —— 仅告警不拒绝（储能设备常做部分上报，如 5s 只报 SOC）。</li>
 * </ul>
 * 拒绝不阻断整条链路：报文仍留痕 iot-raw（rejectReason），标准 topic 不产出。
 * </p>
 *
 * <p>
 * <b>specs 深度校验（M1）</b>：在类型强转之后按 specs 增量校验——数值 min/max/step、字符串 length、struct
 * 字段（structFields，缺字段拒绝）、array 元素类型与个数（elementType/size）。<b>缺少 specs 的历史模型不产生任何新校验</b>
 * （specs 字段为 null 即跳过）；specs 部分存在时只校验实际定义的约束。
 * </p>
 *
 * <p>
 * <b>array-of-struct（M2.1 补齐）</b>：array 且 elementType=struct 时，按 specs.structFields
 * 对每个元素做 struct 递归校验（缺字段/字段类型错误/越界均拒绝），而非仅判断元素是 Map。
 * </p>
 */
public final class ModelValidator {

	private ModelValidator() {
	}

	/** 属性校验结果 */
	public record ValidationResult(boolean valid, Map<String, Object> coerced, List<String> errors) {
	}

	/** 事件检查结果（含级别映射） */
	public record EventCheck(ThingModelEvent event, int severity) {
	}

	/** 深度校验约束（属性/参数通用承载：dataType 之外的 specs 与枚举定义） */
	private record Specs(String identifier, List<EnumValue> enumValues, Double min, Double max, Double step,
			Integer length, String elementType, Integer size, List<ThingModelParam> structFields) {

		/** 无任何约束的空 Specs（未知类型透传 / 递归元素校验用） */
		static Specs empty(String identifier) {
			return new Specs(identifier, null, null, null, null, null, null, null, null);
		}
	}

	/** 步长判定容差（浮点除法的取整余量） */
	private static final double STEP_EPSILON = 1e-6;

	/**
	 * 校验并强转上报属性集合：遍历上报项，未知 identifier 拒绝、类型/枚举非法拒绝， required 缺失仅告警不拒绝；返回强转后的白名单值与错误列表。
	 * @param model 产品物模型（属性白名单与类型定义来源）
	 * @param reported 设备上报的属性键值对
	 * @return 校验结果（valid / 强转后结果 / 错误列表）
	 */
	public static ValidationResult validateProperties(ThingModel model, Map<String, Object> reported) {
		List<String> errors = new ArrayList<>();
		Map<String, Object> coerced = new LinkedHashMap<>();
		if (reported != null) {
			for (Map.Entry<String, Object> entry : reported.entrySet()) {
				ThingModelProperty prop = model.getProperties().get(entry.getKey());
				// 白名单语义：不在物模型中的 identifier 直接拒绝（防脏数据污染宽表/影子）
				if (prop == null) {
					errors.add("unknown identifier: " + entry.getKey());
					continue;
				}
				try {
					coerced.put(entry.getKey(), coerce(prop, entry.getValue()));
				}
				catch (IllegalArgumentException ex) {
					errors.add(ex.getMessage());
				}
			}
		}
		return new ValidationResult(errors.isEmpty(), coerced, errors);
	}

	/**
	 * 按物模型 dataType 强转上报值：数值/布尔/枚举/字符串/struct/array 各自归一并执行 specs 深度校验， 未知类型透传放行。
	 * @param prop 属性定义（提供 dataType、enumValues 与 specs 约束）
	 * @param value 设备上报的原始值（可能为 String 或 Number）
	 * @return 强转后的规范化值（数值归一为 Number，便于写入 TDengine）
	 * @throws IllegalArgumentException 类型不合法 / enum 越界 / 违反 specs 约束
	 */
	public static Object coerce(ThingModelProperty prop, Object value) {
		if (value == null) {
			return null;
		}
		String dataType = prop.getDataType() == null ? "string" : prop.getDataType();
		Specs specs = new Specs(prop.getIdentifier(), prop.getEnumValues(), prop.getMin(), prop.getMax(),
				prop.getStep(), prop.getLength(), prop.getElementType(), prop.getSize(), prop.getStructFields());
		return coerceBySpecs(dataType, specs, value);
	}

	/**
	 * 按服务参数/struct 字段定义强转并深度校验（M2.1：Command 下发参数校验入口）。
	 * @param param 参数定义（提供 dataType、enumValues 与 specs 约束）
	 * @param value 指令参数值
	 * @return 强转后的规范化值
	 * @throws IllegalArgumentException 类型不合法 / enum 越界 / 违反 specs 约束
	 */
	public static Object coerce(ThingModelParam param, Object value) {
		if (value == null) {
			return null;
		}
		String dataType = param.getDataType() == null ? "string" : param.getDataType();
		Specs specs = new Specs(param.getIdentifier(), param.getEnumValues(), param.getMin(), param.getMax(), null,
				param.getLength(), param.getElementType(), param.getSize(), param.getStructFields());
		return coerceBySpecs(dataType, specs, value);
	}

	/**
	 * 事件白名单检查：未知事件返回 null（调用方拒绝）；已知返回事件定义与级别映射。
	 * @param model 产品物模型（事件白名单来源）
	 * @param eventName 上报事件名
	 * @return 命中的 {@link EventCheck}（含级别），未知事件返回 null
	 */
	public static EventCheck checkEvent(ThingModel model, String eventName) {
		ThingModelEvent ev = model.getEvents().get(eventName);
		if (ev == null) {
			return null;
		}
		return new EventCheck(ev, severityOf(ev.getType()));
	}

	/**
	 * 事件级别 → TDengine 严重级别：1提示 2一般 3严重 4危急。
	 * @param type 事件级别枚举（null 或未知按一般 2 处理）
	 * @return TDengine 严重级别数值
	 */
	public static int severityOf(EventSeverity type) {
		if (type == null) {
			return 2;
		}
		return switch (type) {
			case INFO -> 1;
			case ERROR -> 3;
			case CRITICAL -> 4;
			default -> 2; // WARN 及其他
		};
	}

	/**
	 * 按 dataType 与约束执行强转+深度校验（属性/参数/struct 字段/array 元素共用）。
	 * @param dataType 数据类型（null 按 string）
	 * @param specs 深度校验约束
	 * @param value 原始值
	 * @return 强转后的规范化值
	 * @throws IllegalArgumentException 类型不合法 / 越界 / 违反约束
	 */
	private static Object coerceBySpecs(String dataType, Specs specs, Object value) {
		switch (dataType) {
			case "float":
			case "double":
			case "decimal":
			case "number":
				if (value instanceof Number n) {
					double d = n.doubleValue();
					checkNumeric(specs, d);
					return d;
				}
				double parsed = Double.parseDouble(String.valueOf(value).trim());
				checkNumeric(specs, parsed);
				return parsed;
			case "int":
			case "integer":
			case "long":
			case "byte":
			case "short":
				if (value instanceof Number n) {
					long l = n.longValue();
					checkNumeric(specs, l);
					return l;
				}
				long parsedLong = Long.parseLong(String.valueOf(value).trim());
				checkNumeric(specs, parsedLong);
				return parsedLong;
			case "bool":
			case "boolean":
				return coerceBool(value);
			case "enum":
				return coerceEnum(specs, value);
			case "string":
			case "text":
			case "date":
			case "time":
				String s = String.valueOf(value);
				checkLength(specs, s);
				return s;
			case "struct":
				return coerceStruct(specs, value);
			case "array":
				return coerceArray(specs, value);
			default:
				// 未知 dataType：透传，保守放行（历史行为不变）
				return value;
		}
	}

	/** 数值范围/步长校验：min/max 越界拒绝；step 在 min 已定义时按步长取整校验（容差 STEP_EPSILON） */
	private static void checkNumeric(Specs specs, double v) {
		if (specs.min() != null && v < specs.min()) {
			throw new IllegalArgumentException(
					"数值越界（小于 min）identifier=" + specs.identifier() + " min=" + specs.min() + " value=" + v);
		}
		if (specs.max() != null && v > specs.max()) {
			throw new IllegalArgumentException(
					"数值越界（大于 max）identifier=" + specs.identifier() + " max=" + specs.max() + " value=" + v);
		}
		if (specs.step() != null && specs.min() != null && specs.step() > 0) {
			double q = (v - specs.min()) / specs.step();
			if (Math.abs(q - Math.round(q)) > STEP_EPSILON) {
				throw new IllegalArgumentException("数值不满足步长 identifier=" + specs.identifier() + " step=" + specs.step()
						+ " min=" + specs.min() + " value=" + v);
			}
		}
	}

	/** 字符串长度校验：length 已定义且超限时拒绝 */
	private static void checkLength(Specs specs, String s) {
		if (specs.length() != null && s.length() > specs.length()) {
			throw new IllegalArgumentException(
					"字符串超长 identifier=" + specs.identifier() + " max=" + specs.length() + " length=" + s.length());
		}
	}

	/** struct 强转：要求对象；定义 structFields 时逐字段强转，缺字段拒绝（未定义时仅要求对象，历史行为） */
	@SuppressWarnings("unchecked")
	private static Object coerceStruct(Specs specs, Object value) {
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("struct 类型需对象，identifier=" + specs.identifier());
		}
		Map<String, Object> map = (Map<String, Object>) value;
		List<ThingModelParam> fields = specs.structFields();
		if (fields == null || fields.isEmpty()) {
			return map;
		}
		Map<String, Object> coerced = new LinkedHashMap<>(map);
		for (ThingModelParam field : fields) {
			Object fv = map.get(field.getIdentifier());
			if (fv == null) {
				throw new IllegalArgumentException(
						"struct 缺字段 identifier=" + specs.identifier() + " field=" + field.getIdentifier());
			}
			coerced.put(field.getIdentifier(), coerceField(field, fv));
		}
		return coerced;
	}

	/** struct 内单个字段按 {@link ThingModelParam} 定义强转（支持嵌套 struct/array，enum 走参数枚举定义） */
	private static Object coerceField(ThingModelParam field, Object value) {
		if (value == null) {
			return null;
		}
		String dataType = field.getDataType() == null ? "string" : field.getDataType();
		Specs specs = new Specs(field.getIdentifier(), field.getEnumValues(), field.getMin(), field.getMax(), null,
				field.getLength(), field.getElementType(), field.getSize(), field.getStructFields());
		return coerceBySpecs(dataType, specs, value);
	}

	/**
	 * array 强转：要求数组；size 超限拒绝；elementType 已定义时逐元素按元素类型强转 （elementType=struct 且有
	 * structFields 时按 struct 递归校验，M2.1 补齐 array-of-struct； 未定义 elementType 时透传，历史行为）。
	 */
	@SuppressWarnings("unchecked")
	private static Object coerceArray(Specs specs, Object value) {
		if (!(value instanceof List)) {
			throw new IllegalArgumentException("array 类型需数组，identifier=" + specs.identifier());
		}
		List<Object> list = (List<Object>) value;
		if (specs.size() != null && list.size() > specs.size()) {
			throw new IllegalArgumentException(
					"array 超长 identifier=" + specs.identifier() + " max=" + specs.size() + " size=" + list.size());
		}
		if (specs.elementType() != null && !specs.elementType().isBlank()) {
			List<Object> coerced = new ArrayList<>(list.size());
			for (Object item : list) {
				coerced.add(coerceElement(specs, item));
			}
			return coerced;
		}
		return list;
	}

	/** array 元素强转：elementType=struct 且有 structFields 时逐元素 struct 递归校验，否则按元素类型强转 */
	private static Object coerceElement(Specs specs, Object item) {
		if ("struct".equals(specs.elementType()) && specs.structFields() != null && !specs.structFields().isEmpty()) {
			return coerceStruct(specs, item);
		}
		return coerceBySpecs(specs.elementType(), Specs.empty(specs.identifier()), item);
	}

	/**
	 * 布尔强转：Boolean 原样；字符串 "1"/"true"/"on" → true，"0"/"false"/"off" → false。
	 * @param value 原始值
	 * @return 归一化布尔值
	 * @throws IllegalArgumentException 非上述取值时抛出
	 */
	private static boolean coerceBool(Object value) {
		if (value instanceof Boolean b) {
			return b;
		}
		String s = String.valueOf(value).trim();
		if ("1".equals(s) || "true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
			return true;
		}
		if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
			return false;
		}
		throw new IllegalArgumentException("bool 值非法: " + s);
	}

	/**
	 * 枚举强转：在枚举定义中按归一化值匹配；命中返回模型登记的规范值， 未命中抛异常（拒绝越界取值，防止脏数据写入宽表/影子）。
	 * 无枚举定义（历史模型/未知类型）时原样透传，保持原行为。
	 * @param specs 属性/参数约束（含 enumValues）
	 * @param value 上报原始值
	 * @return 模型登记的规范枚举值
	 * @throws IllegalArgumentException 取值不在枚举范围内时抛出
	 */
	private static Object coerceEnum(Specs specs, Object value) {
		List<EnumValue> enumValues = specs.enumValues();
		if (enumValues == null || enumValues.isEmpty()) {
			return value;
		}
		for (EnumValue ev : enumValues) {
			if (Objects.equals(normalize(ev.getValue()), normalize(value))) {
				return canonical(ev.getValue());
			}
		}
		throw new IllegalArgumentException("enum 取值越界 identifier=" + specs.identifier() + " value=" + value);
	}

	/**
	 * 返回模型登记的规范值：数值归一为数值类型（TDengine INT 列无法写入字符串 "2"），否则原样。
	 * @param v 枚举定义中的原始值
	 * @return 规范化后的规范值
	 */
	private static Object canonical(Object v) {
		if (v instanceof Number n) {
			return n;
		}
		try {
			return Long.parseLong(String.valueOf(v).trim());
		}
		catch (NumberFormatException ignore) {
			return v;
		}
	}

	/**
	 * 归一化用于比较：数值转为 long 字符串，其余转字符串，使 "2" 与 2 视为相等。
	 * @param v 待比较值
	 * @return 归一化后的字符串
	 */
	private static String normalize(Object v) {
		if (v instanceof Number n) {
			return String.valueOf(n.longValue());
		}
		return String.valueOf(v);
	}

}
