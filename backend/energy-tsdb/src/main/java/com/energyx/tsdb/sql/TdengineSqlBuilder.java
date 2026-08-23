package com.energyx.tsdb.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * TDengine 写入 SQL 纯构造器（无副作用，便于单测），与 sql/tdengine/10_stable.sql 约定对齐：
 *
 * <ul>
 * <li>属性宽表 {@code st_prop_{productKey}}（iot_tsdb_raw），子表 {@code dev_{deviceId}}； 公共列
 * ts/msg_id/data_type + 物模型 identifier 列（消息携带哪些就写哪些，缺省为 NULL）； 列名反引号包裹以兼容 TDengine
 * 保留字。</li>
 * <li>事件表 {@code st_event}（iot_tsdb_event），子表 {@code dev_{deviceId}_evt}； 列
 * ts/event_id/event_name/severity/code/payload(JSON 列)。</li>
 * <li>自动建子表：{@code INSERT INTO child USING stable TAGS (...)}；ts 用 epoch 毫秒
 * 字面量（无时区歧义）；多行多列由批量缓冲合并成一条语句执行。</li>
 * <li>TAGS 冗余 device_id/station_id/enterprise_id/product_key，null 归一为 ''，
 * 避免同一设备因标签差异被拆成多个子表。</li>
 * </ul>
 */
public final class TdengineSqlBuilder {

	/** 合法列名/表名片段：字母下划线开头，仅字母数字下划线 */
	private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	/** productKey 允许含数字开头（如 snd_ess_pcs） */
	private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_]+");

	private TdengineSqlBuilder() {
	}

	/** 物模型 identifier 是否可直接作为列名 */
	public static boolean isSafeColumn(String identifier) {
		return identifier != null && SAFE_IDENTIFIER.matcher(identifier).matches();
	}

	/** productKey 是否可作为 stable 名片段 */
	public static boolean isSafeKey(String key) {
		return key != null && SAFE_KEY.matcher(key).matches();
	}

	/**
	 * 物模型 dataType → TDengine 列类型统一映射（M3.1）。
	 *
	 * <p>
	 * 依据：消息值经 {@code ModelValidator.coerce} 强转后的字面量形态——数值枚举规范值为 Long（数字字面量）， struct/array
	 * 以 JSON 字符串字面量存储（保守用 NCHAR，不赌 TDengine JSON 列行为）。
	 * </p>
	 */
	public static String columnType(String dataType) {
		if (dataType == null) {
			return "NCHAR(1024)";
		}
		return switch (dataType) {
			case "float", "double", "decimal", "number" -> "FLOAT";
			case "int", "integer", "byte", "short" -> "INT";
			case "long", "enum" -> "BIGINT";
			case "bool", "boolean" -> "BOOL";
			case "string", "text", "date", "time" -> "NCHAR(64)";
			case "struct", "array" -> "NCHAR(1024)";
			default -> "NCHAR(1024)"; // 未知 dataType：JSON 字符串兜底
		};
	}

	/** epoch 毫秒 → TDengine TIMESTAMP 字面量（无时区歧义） */
	public static String tsLiteral(long epochMs) {
		return Long.toString(epochMs);
	}

	/** 字符串 → 单引号 NCHAR/JSON 字面量（转义反斜杠与单引号） */
	public static String strLiteral(String s) {
		if (s == null) {
			return "NULL";
		}
		return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	/**
	 * 值 → SQL 字面量：bool→true/false；数值原样；字符串转义引号； 容器（struct/list）序列化为 JSON 字符串（JSON/NCHAR
	 * 列均可承载）。
	 */
	public static String literal(Object value, ObjectMapper om) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Boolean b) {
			return b ? "true" : "false";
		}
		if (value instanceof Number n) {
			return n.toString();
		}
		if (value instanceof String s) {
			return strLiteral(s);
		}
		try {
			return strLiteral(om.writeValueAsString(value));
		}
		catch (Exception e) {
			throw new IllegalArgumentException("无法序列化属性值: " + value, e);
		}
	}

	/** 构造属性宽表写入语句 */
	public static String buildPropertyInsert(ThingPropertyMessage m, String db, ObjectMapper om) {
		require(m.getDeviceId() != null, "deviceId 为空，无法落库");
		require(isSafeKey(m.getProductKey()), "productKey 非法: " + m.getProductKey());
		String child = "dev_" + m.getDeviceId();
		String stable = "st_prop_" + m.getProductKey();

		StringBuilder cols = new StringBuilder("ts, msg_id, data_type");
		StringBuilder vals = new StringBuilder().append(tsLiteral(orDefault(m.getTs(), System.currentTimeMillis())))
			.append(", ")
			.append(strLiteral(m.getMessageId()))
			.append(", ")
			.append(strLiteral(orDefault(m.getDataType(), "report")));

		for (Map.Entry<String, Object> e : m.getProperties().entrySet()) {
			String id = e.getKey();
			if (!isSafeColumn(id)) {
				continue; // 非法列名（物模型已保证，防御性跳过）
			}
			// TDengine 3.3.1.0 实测：列名匹配统一转小写（反引号驼峰列写不进），列名一律小写化
			cols.append(", `").append(id.toLowerCase()).append('`');
			vals.append(", ").append(literal(e.getValue(), om));
		}

		return "INSERT INTO " + db + "." + child + " USING " + db + "." + stable + " TAGS (" + tagLiteral(m) + ") ("
				+ cols + ") VALUES (" + vals + ")";
	}

	/**
	 * 构造属性超级表建表语句（首条消息落库前自动建表）。 列定义：公共列(ts/msg_id/data_type) + 属性列 （类型按物模型 dataType
	 * 统一映射，M3.1）；TAG 四件套。列类型映射见 {@link #columnType(String)}。
	 * @param productKey 产品标识
	 * @param db 数据库名
	 * @param columnTypes 属性 identifier → TDengine 列类型（顺序=声明顺序）
	 */
	public static String buildCreatePropertyStable(String productKey, String db,
			java.util.Map<String, String> columnTypes) {
		require(isSafeKey(productKey), "productKey 非法: " + productKey);
		StringBuilder sb = new StringBuilder("CREATE STABLE IF NOT EXISTS ").append(db)
			.append(".st_prop_")
			.append(productKey)
			.append(" (ts TIMESTAMP, msg_id NCHAR(64), data_type NCHAR(16)");
		for (java.util.Map.Entry<String, String> e : columnTypes.entrySet()) {
			if (isSafeColumn(e.getKey())) {
				// TDengine 列名一律小写化（实机验证：反引号驼峰列 INSERT/SELECT 均失败）
				sb.append(", `").append(e.getKey().toLowerCase()).append("` ").append(e.getValue());
			}
		}
		sb.append(") TAGS (device_id NCHAR(64), station_id NCHAR(32), enterprise_id NCHAR(32), product_key NCHAR(64))");
		return sb.toString();
	}

	/**
	 * 构造属性超级表单列加列语句（M3.1：物模型新增属性时自动演进 stable）。
	 *
	 * <p>
	 * <b>实机验证（TDengine 3.3.1.0）</b>：{@code ALTER STABLE ... ADD COLUMN c1 t1, c2 t2}
	 * 多列语法不被支持 （0x2600 syntax error），必须<b>一次 ADD 一列</b>——调用方对缺失列逐列生成并执行。 stable 名必须为
	 * {@code st_prop_} 前缀且安全字符。
	 * </p>
	 * @param db 数据库名
	 * @param stable stable 名（含 st_prop_ 前缀）
	 * @param column 缺失属性 identifier
	 * @param type TDengine 列类型（见 {@link #columnType(String)}）
	 * @return ALTER STABLE DDL（单列）
	 */
	public static String buildAlterStableSql(String db, String stable, String column, String type) {
		require(stable != null && stable.startsWith("st_prop_"), "stable 非法: " + stable);
		require(isSafeKey(stable.substring("st_prop_".length())), "stable 非法: " + stable);
		require(isSafeColumn(column), "列名非法: " + column);
		require(type != null && !type.isBlank(), "列类型为空: " + column);
		// TDengine 列名一律小写化（实机验证：反引号驼峰列 INSERT/SELECT 均失败）
		return "ALTER STABLE " + db + "." + stable + " ADD COLUMN `" + column.toLowerCase() + "` " + type;
	}

	/**
	 * 从批量 INSERT 语句解析需自动建表的属性超级表：{stable → 本批 INSERT 携带的属性列集}。 正则匹配
	 * {@code USING db.st_prop_xxx ... (ts, msg_id, data_type, col1, col2) VALUES} 的
	 * stable 名与列名段，属性列 = 列名段剔除公共列（ts/msg_id/data_type）。建表依赖本方法保证
	 * 「超级表存在」前置，列仅来自首条消息集——后续新列需 ALTER（与 buildCreatePropertyStable 注释一致）。
	 */
	public static java.util.Map<String, java.util.Set<String>> extractPropertyStables(String batchSql, String db) {
		java.util.Map<String, java.util.Set<String>> result = new java.util.LinkedHashMap<>();
		if (batchSql == null) {
			return result;
		}
		java.util.regex.Matcher m = STABLE_INSERT_PATTERN.matcher(batchSql);
		while (m.find()) {
			String stable = m.group(1);
			String colsRaw = m.group(2);
			java.util.Set<String> cols = new java.util.LinkedHashSet<>();
			for (String c : colsRaw.split(",")) {
				String id = c.trim().replace("`", "");
				if (!id.isEmpty() && !COMMON_COLUMN_SET.contains(id)) {
					cols.add(id);
				}
			}
			result.merge(stable, cols, (a, b) -> {
				a.addAll(b);
				return a;
			});
		}
		return result;
	}

	/**
	 * INSERT...USING db.st_prop_xxx TAGS (...) (列名段) VALUES 匹配：组1=stable 名（含 st_prop_
	 * 前缀），组2=列名段
	 */
	private static final java.util.regex.Pattern STABLE_INSERT_PATTERN = java.util.regex.Pattern
		.compile("USING\\s+\\S+?\\.(st_prop_[A-Za-z0-9_]+)\\s+TAGS\\s*\\([^)]*\\)\\s*\\(([^)]*)\\)");

	/** 公共列集合（建表/白名单排除） */
	private static final java.util.Set<String> COMMON_COLUMN_SET = java.util.Set.of("ts", "msg_id", "data_type");

	/** 构造事件落库语句（st_event，payload JSON 列） */
	public static String buildEventInsert(ThingEventMessage m, String db, ObjectMapper om) {
		require(m.getDeviceId() != null, "deviceId 为空，无法落库");
		String child = "dev_" + m.getDeviceId() + "_evt";
		String payload = (m.getData() != null && !m.getData().isEmpty()) ? literal(m.getData(), om) : "NULL";

		return "INSERT INTO " + db + "." + child + " USING " + db + ".st_event" + " TAGS ("
				+ tagLiteral(m.getDeviceId(), m.getStationId(), m.getEnterpriseId(), m.getProductKey()) + ") ("
				+ "ts, event_id, event_name, severity, code, payload) VALUES ("
				+ tsLiteral(orDefault(m.getTs(), System.currentTimeMillis())) + ", " + strLiteral(m.getEventId()) + ", "
				+ strLiteral(m.getEventName()) + ", " + (m.getSeverity() == null ? "NULL" : m.getSeverity().toString())
				+ ", " + strLiteral(m.getCode()) + ", " + payload + ")";
	}

	private static String tagLiteral(ThingPropertyMessage m) {
		return tagLiteral(m.getDeviceId(), m.getStationId(), m.getEnterpriseId(), m.getProductKey());
	}

	/** 标签值统一 NCHAR 字面量，null → ''（保持子表身份一致） */
	private static String tagLiteral(Long deviceId, Long stationId, Long enterpriseId, String productKey) {
		return strLiteral(deviceId == null ? "" : deviceId.toString()) + ", "
				+ strLiteral(stationId == null ? "" : stationId.toString()) + ", "
				+ strLiteral(enterpriseId == null ? "" : enterpriseId.toString()) + ", "
				+ strLiteral(productKey == null ? "" : productKey);
	}

	private static long orDefault(Long v, long def) {
		return v == null ? def : v;
	}

	private static <T> T orDefault(T v, T def) {
		return v == null ? def : v;
	}

	private static void require(boolean cond, String msg) {
		if (!cond) {
			throw new IllegalArgumentException(msg);
		}
	}

}
