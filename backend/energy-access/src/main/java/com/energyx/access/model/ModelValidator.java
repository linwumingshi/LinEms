package com.energyx.access.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物模型上报校验 + 类型强转（纯函数，无 I/O）。
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

	public static ValidationResult validateProperties(ThingModel model, Map<String, Object> reported) {
		List<String> errors = new ArrayList<>();
		Map<String, Object> coerced = new LinkedHashMap<>();
		if (reported != null) {
			for (Map.Entry<String, Object> entry : reported.entrySet()) {
				ThingModelProperty prop = model.getProperties().get(entry.getKey());
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
	 * 按物模型 dataType 强转上报值。
	 * @throws IllegalArgumentException 类型不合法 / enum 越界
	 */
	public static Object coerce(ThingModelProperty prop, Object value) {
		if (value == null) {
			return null;
		}
		String dataType = prop.getDataType() == null ? "string" : prop.getDataType();
		switch (dataType) {
			case "float":
			case "double":
			case "decimal":
			case "number":
				if (value instanceof Number n) {
					return n.doubleValue();
				}
				return Double.parseDouble(String.valueOf(value).trim());
			case "int":
			case "integer":
			case "long":
			case "byte":
			case "short":
				if (value instanceof Number n) {
					return n.longValue();
				}
				return Long.parseLong(String.valueOf(value).trim());
			case "bool":
			case "boolean":
				return coerceBool(value);
			case "enum":
				return coerceEnum(prop, value);
			case "string":
			case "text":
			case "date":
			case "time":
				return String.valueOf(value);
			case "struct":
				if (value instanceof Map) {
					return value;
				}
				throw new IllegalArgumentException("struct 类型需对象，identifier=" + prop.getIdentifier());
			default:
				// 未知 dataType：透传，保守放行
				return value;
		}
	}

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

	private static Object coerceEnum(ThingModelProperty prop, Object value) {
		List<EnumValue> enumValues = prop.getEnumValues();
		if (enumValues == null || enumValues.isEmpty()) {
			return value;
		}
		for (EnumValue ev : enumValues) {
			if (Objects.equals(normalize(ev.getValue()), normalize(value))) {
				return canonical(ev.getValue());
			}
		}
		throw new IllegalArgumentException("enum 取值越界 identifier=" + prop.getIdentifier() + " value=" + value);
	}

	/** 返回模型登记的规范值：数值归一为数值类型（TDengine INT 列无法写入字符串 "2"），否则原样 */
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

	private static String normalize(Object v) {
		if (v instanceof Number n) {
			return String.valueOf(n.longValue());
		}
		return String.valueOf(v);
	}

	/** 事件白名单检查：未知事件返回 null（调用方拒绝）；已知返回级别映射。 */
	public static EventCheck checkEvent(ThingModel model, String eventName) {
		ThingModelEvent ev = model.getEvents().get(eventName);
		if (ev == null) {
			return null;
		}
		return new EventCheck(ev, severityOf(ev.getType()));
	}

	/** 事件类型 → TDengine 严重级别：1提示 2一般 3严重 4危急 */
	public static int severityOf(String type) {
		if (type == null) {
			return 2;
		}
		return switch (type.toUpperCase()) {
			case "INFO" -> 1;
			case "ERROR" -> 3;
			case "CRITICAL" -> 4;
			default -> 2; // WARN 及其他
		};
	}

}
