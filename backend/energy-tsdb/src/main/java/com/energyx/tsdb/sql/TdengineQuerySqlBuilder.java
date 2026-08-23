package com.energyx.tsdb.sql;

import java.util.List;

/**
 * TDengine 历史查询 SQL 纯构造器（无副作用，便于单测）。
 *
 * <p>
 * 与 {@link TdengineSqlBuilder} 共享 isSafeKey/isSafeColumn 约定；请求的 identifiers 已由 service 层
 * DESCRIBE 白名单过滤，此处仅做防御性安全校验（禁止拼接未校验列名）。 列名反引号包裹；device_id/ts 均为 {@code ?} 占位符，由 service
 * 层 PreparedStatement 绑定。
 * </p>
 */
public final class TdengineQuerySqlBuilder {

	private TdengineQuerySqlBuilder() {
	}

	/**
	 * 属性历史 data 查询：SELECT ts, `id1`, `id2` ... FROM {db}.st_prop_{productKey} WHERE ...
	 * ORDER BY ts ... LIMIT ? OFFSET ?
	 */
	public static String buildDataSql(String db, String productKey, List<String> identifiers, String deviceId,
			long startTime, long endTime, boolean asc, int limit, int offset) {
		if (!TdengineSqlBuilder.isSafeKey(productKey)) {
			throw new IllegalArgumentException("productKey 非法: " + productKey);
		}
		StringBuilder cols = new StringBuilder("ts");
		for (String id : identifiers) {
			if (!TdengineSqlBuilder.isSafeColumn(id)) {
				throw new IllegalArgumentException("非法属性标识: " + id);
			}
			// TDengine 3.3.1.0 实测：SELECT 列名统一转小写匹配（反引号驼峰列查不出）
			cols.append(", `").append(id.toLowerCase()).append('`');
		}
		return "SELECT " + cols + " FROM " + db + ".st_prop_" + productKey
				+ " WHERE device_id = ? AND ts >= ? AND ts <= ?" + " ORDER BY ts " + (asc ? "ASC" : "DESC")
				+ " LIMIT ? OFFSET ?";
	}

	/** 属性历史 count 查询（同过滤条件，供分页 total）。 */
	public static String buildCountSql(String db, String productKey, String deviceId, long startTime, long endTime) {
		if (!TdengineSqlBuilder.isSafeKey(productKey)) {
			throw new IllegalArgumentException("productKey 非法: " + productKey);
		}
		return "SELECT count(*) FROM " + db + ".st_prop_" + productKey + " WHERE device_id = ? AND ts >= ? AND ts <= ?";
	}

}
