package com.sanduo.energy.tsdb.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanduo.energy.common.message.ThingEventMessage;
import com.sanduo.energy.common.message.ThingPropertyMessage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * TDengine 写入 SQL 纯构造器（无副作用，便于单测），与 sql/tdengine/10_stable.sql 约定对齐：
 *
 * <ul>
 *   <li>属性宽表 {@code st_prop_{productKey}}（iot_tsdb_raw），子表 {@code dev_{deviceId}}；
 *       公共列 ts/msg_id/data_type + 物模型 identifier 列（消息携带哪些就写哪些，缺省为 NULL）；
 *       列名反引号包裹以兼容 TDengine 保留字。</li>
 *   <li>事件表 {@code st_event}（iot_tsdb_event），子表 {@code dev_{deviceId}_evt}；
 *       列 ts/event_id/event_name/severity/code/payload(JSON 列)。</li>
 *   <li>自动建子表：{@code INSERT INTO child USING stable TAGS (...)}；ts 用 epoch 毫秒
 *       字面量（无时区歧义）；多行多列由批量缓冲合并成一条语句执行。</li>
 *   <li>TAGS 冗余 device_id/station_id/enterprise_id/product_key，null 归一为 ''，
 *       避免同一设备因标签差异被拆成多个子表。</li>
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
     * 值 → SQL 字面量：bool→true/false；数值原样；字符串转义引号；
     * 容器（struct/list）序列化为 JSON 字符串（JSON/NCHAR 列均可承载）。
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
        } catch (Exception e) {
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
        StringBuilder vals = new StringBuilder()
                .append(tsLiteral(orDefault(m.getTs(), System.currentTimeMillis())))
                .append(", ").append(strLiteral(m.getMessageId()))
                .append(", ").append(strLiteral(orDefault(m.getDataType(), "report")));

        for (Map.Entry<String, Object> e : m.getProperties().entrySet()) {
            String id = e.getKey();
            if (!isSafeColumn(id)) {
                continue; // 非法列名（物模型已保证，防御性跳过）
            }
            cols.append(", `").append(id).append('`');
            vals.append(", ").append(literal(e.getValue(), om));
        }

        return "INSERT INTO " + db + "." + child
                + " USING " + db + "." + stable
                + " TAGS (" + tagLiteral(m) + ") ("
                + cols + ") VALUES (" + vals + ")";
    }

    /** 构造事件落库语句（st_event，payload JSON 列） */
    public static String buildEventInsert(ThingEventMessage m, String db, ObjectMapper om) {
        require(m.getDeviceId() != null, "deviceId 为空，无法落库");
        String child = "dev_" + m.getDeviceId() + "_evt";
        String payload = (m.getData() != null && !m.getData().isEmpty())
                ? literal(m.getData(), om)
                : "NULL";

        return "INSERT INTO " + db + "." + child
                + " USING " + db + ".st_event"
                + " TAGS (" + tagLiteral(m.getDeviceId(), m.getStationId(), m.getEnterpriseId(), m.getProductKey()) + ") ("
                + "ts, event_id, event_name, severity, code, payload) VALUES ("
                + tsLiteral(orDefault(m.getTs(), System.currentTimeMillis()))
                + ", " + strLiteral(m.getEventId())
                + ", " + strLiteral(m.getEventName())
                + ", " + (m.getSeverity() == null ? "NULL" : m.getSeverity().toString())
                + ", " + strLiteral(m.getCode())
                + ", " + payload + ")";
    }

    private static String tagLiteral(ThingPropertyMessage m) {
        return tagLiteral(m.getDeviceId(), m.getStationId(), m.getEnterpriseId(), m.getProductKey());
    }

    /** 标签值统一 NCHAR 字面量，null → ''（保持子表身份一致） */
    private static String tagLiteral(Long deviceId, Long stationId, Long enterpriseId, String productKey) {
        return strLiteral(deviceId == null ? "" : deviceId.toString())
                + ", " + strLiteral(stationId == null ? "" : stationId.toString())
                + ", " + strLiteral(enterpriseId == null ? "" : enterpriseId.toString())
                + ", " + strLiteral(productKey == null ? "" : productKey);
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
