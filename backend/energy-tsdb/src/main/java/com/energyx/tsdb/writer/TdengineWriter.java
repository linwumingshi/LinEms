package com.energyx.tsdb.writer;

import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelProperty;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.service.TdengineQueryService;
import com.energyx.tsdb.sql.TdengineSqlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TDengine JDBC 写入器（taos-jdbcdriver / TAOS-RS 连接）。
 *
 * <p>
 * 单连接 + 整语句批量执行：TDengine 原生支持一条语句内多个 INSERT 块 （自动建子表 + 多行多列），写入吞吐由批量缓冲的行数/字节阈值控制。
 * 连接失效时重建后重试一次，仍失败则上抛交由消费端进 DLQ。
 * </p>
 *
 * <p>
 * <b>M3.1 列演进</b>：SQLException 处理分三支——<b>表不存在</b> → 自动建表（
 * {@link #autoCreateStables(String)}，列类型按物模型 dataType 映射，无模型降级 FLOAT）；<b>列不存在</b> → 自动
 * ALTER（{@link #autoAlterStables(String)}，DESCRIBE 差集 + Resolver 物模型 + ADD COLUMN）；
 * 其余错误保持连接重建/重试语义。ALTER/建表成功后仅重试 1 次，绝不无限重试；物模型缺失时跳过 ALTER，保持最终失败/DLQ。
 * </p>
 */
@Slf4j
@Component
public class TdengineWriter implements TsdbWriter {

	/**
	 * TDengine column-not-exist 错误码（taos-jdbcdriver 3.9.0，TDengine 3.3.1.0 实机验证）：JDBC
	 * 消息内嵌 0x2602，十进制即 REST 透传错误码 9730（同一错误码两种表示，实机已确认）。文本匹配兜底保留（防措辞变化），双保险。
	 */
	private static final Set<Integer> COLUMN_MISSING_CODES = Set.of(0x2602);

	/** 缺列错误文本特征（大小写不敏感，覆盖 TDengine/REST 常见措辞） */
	private static final String[] COLUMN_MISSING_TEXTS = { "column does not exist", "column not exist",
			"invalid column", "column not found", "no such column", "unknown column" };

	private final TsdbProperties props;

	private final ThingModelResolver thingModelResolver;

	private final TdengineQueryService queryService;

	private volatile Connection connection;

	public TdengineWriter(TsdbProperties props, ThingModelResolver thingModelResolver,
			TdengineQueryService queryService) {
		this.props = props;
		this.thingModelResolver = thingModelResolver;
		this.queryService = queryService;
	}

	@Override
	public void execute(String sql) throws Exception {
		int attempt = 0;
		// ALTER/建表各自最多触发 1 次：成功后仅重试原 SQL，绝不因同一错误无限 ALTER/重试
		boolean altered = false;
		boolean created = false;
		while (true) {
			try {
				Connection conn = getConnection();
				try (Statement st = conn.createStatement()) {
					st.execute(sql);
				}
				return;
			}
			catch (SQLException e) {
				// 1. 超级表不存在（REST 9731 / JDBC 0x2603）：自动建表后重试（至多 1 次）
				if (!created && isTableMissing(e) && attempt == 0 && autoCreateStables(sql)) {
					created = true;
					log.info("[Tsdb] 已自动建属性超级表，重试写入");
					continue;
				}
				// 2. 缺列（物模型新增属性）：自动 ALTER 后重试（至多 1 次，绝无无限重试）
				if (!altered && isColumnMissing(e) && attempt == 0 && autoAlterStables(sql)) {
					altered = true;
					log.info("[Tsdb] 已自动 ALTER 属性超级表，重试写入");
					continue;
				}
				attempt++;
				closeQuietly();
				if (attempt >= 2) {
					log.error("[Tsdb] TDengine 写入失败（已重试） errorCode={} sqlState={} sql={}", e.getErrorCode(),
							e.getSQLState(), abbreviate(sql), e);
					throw e;
				}
				log.warn("[Tsdb] TDengine 写入异常，重建连接重试 errorCode={} sqlState={}", e.getErrorCode(), e.getSQLState(), e);
			}
		}
	}

	/** 从批内 INSERT 解析属性超级表并逐个 CREATE STABLE IF NOT EXISTS；返回是否建了表 */
	private boolean autoCreateStables(String sql) throws Exception {
		Map<String, Set<String>> stables = TdengineSqlBuilder.extractPropertyStables(sql, props.getRawDb());
		if (stables.isEmpty()) {
			return false;
		}
		Connection conn = getConnection();
		try (Statement st = conn.createStatement()) {
			for (Map.Entry<String, Set<String>> e : stables.entrySet()) {
				String stable = e.getKey();
				String productKey = stable.substring("st_prop_".length());
				Map<String, String> columnTypes = modelColumnTypes(productKey, e.getValue());
				String ddl = TdengineSqlBuilder.buildCreatePropertyStable(productKey, props.getRawDb(), columnTypes);
				st.execute(ddl);
				log.info("[Tsdb] 自动建属性超级表 stable={} columns={}", stable, columnTypes.keySet());
			}
		}
		return true;
	}

	/**
	 * 缺列自动 ALTER：对 batch 中每个属性超级表——DESCRIBE 当前列集 → Resolver 物模型全集 → 差集 → 逐列 ADD COLUMN
	 * （类型按 dataType 映射，TDengine 3.3.1.0 一次仅支持单列 ADD，实机验证）→ 失效列缓存。全部涉及 stable 成功返回 true。
	 *
	 * <p>
	 * 物模型缺失/解析失败 → 该 stable 跳过 ALTER 并返回 false（保持最终失败/DLQ，不伪造列定义）； 「列已存在」类并发错误幂等容错；其余
	 * ALTER 异常原样上抛。
	 * </p>
	 */
	private boolean autoAlterStables(String sql) throws Exception {
		Map<String, Set<String>> stables = TdengineSqlBuilder.extractPropertyStables(sql, props.getRawDb());
		if (stables.isEmpty()) {
			return false;
		}
		Connection conn = getConnection();
		try (Statement st = conn.createStatement()) {
			for (Map.Entry<String, Set<String>> e : stables.entrySet()) {
				String stable = e.getKey();
				String productKey = stable.substring("st_prop_".length());
				Set<String> currentCols = queryService.propertyColumns(productKey);
				// TDengine 列名大小写不敏感（DESCRIBE 返回小写，实机验证）；物模型 identifier 保留原大小写 → 小写比较
				Set<String> currentLower = currentCols.stream()
					.map(String::toLowerCase)
					.collect(java.util.stream.Collectors.toSet());
				ThingModel model = thingModelResolver.resolve(productKey);
				if (model == null || model.getProperties().isEmpty()) {
					log.warn("[Tsdb] 物模型缺失/获取失败，跳过自动 ALTER stable={} productKey={}", stable, productKey);
					return false;
				}
				Map<String, String> missing = new LinkedHashMap<>();
				for (ThingModelProperty prop : distinctCanonicalProperties(model)) {
					String canon = prop.getIdentifier().toLowerCase();
					if (currentLower.contains(canon)) {
						continue; // 该列已存在
					}
					missing.put(prop.getIdentifier(), TdengineSqlBuilder.columnType(prop.getDataType()));
				}
				if (missing.isEmpty()) {
					continue; // 该 stable 无缺列（错误可能来自批内其他 stable）
				}
				for (Map.Entry<String, String> col : missing.entrySet()) {
					String ddl = TdengineSqlBuilder.buildAlterStableSql(props.getRawDb(), stable, col.getKey(),
							col.getValue());
					try {
						st.execute(ddl);
						log.info("[Tsdb] 自动 ALTER stable={} column={}", stable, col.getKey());
					}
					catch (SQLException alterEx) {
						if (isColumnAlreadyExists(alterEx)) {
							log.info("[Tsdb] ALTER 列已存在（并发幂等） stable={} column={}", stable, col.getKey());
						}
						else {
							throw alterEx; // 其他 ALTER 异常不得吞掉
						}
					}
				}
				queryService.invalidateColumnCache(productKey);
			}
		}
		return true;
	}

	/**
	 * 建表列类型：优先物模型属性全集（类型按 dataType 映射，一次建全）；物模型缺失时降级为 batch 携带列集全 FLOAT （保持历史行为，表仍能建、列后续经
	 * ALTER 演进）。两条路径均做 canonical 列名冲突过滤（{@link #distinctCanonicalProperties} 或等价去重）。
	 */
	private Map<String, String> modelColumnTypes(String productKey, Set<String> batchCols) {
		Map<String, String> types = new LinkedHashMap<>();
		ThingModel model = thingModelResolver.resolve(productKey);
		if (model != null && !model.getProperties().isEmpty()) {
			for (ThingModelProperty prop : distinctCanonicalProperties(model)) {
				types.put(prop.getIdentifier(), TdengineSqlBuilder.columnType(prop.getDataType()));
			}
			return types;
		}
		Set<String> seenCanon = new HashSet<>();
		for (String id : batchCols) {
			String canon = id.toLowerCase();
			if (!seenCanon.add(canon)) {
				log.warn("[Tsdb] batch 列名 canonical 冲突，跳过后续列 identifier={} canonical={}", id, canon);
				continue;
			}
			types.put(id, "FLOAT");
		}
		return types;
	}

	/**
	 * canonical 列名冲突过滤（M3.1.5）：物模型属性经 {@code identifier.toLowerCase()} 折叠后若重名 （如
	 * {@code foo} 与 {@code Foo}），TDengine 只会有一个物理列——保留声明序首个属性，后续冲突属性 log.warn
	 * 并跳过，不静默覆盖、不生成重复物理列。非冲突属性（如 {@code foo}/{@code FooBar}）不受影响。
	 * @param model 物模型（调用方保证非空）
	 * @return 无 canonical 冲突的属性列表（声明序）
	 */
	private static List<ThingModelProperty> distinctCanonicalProperties(ThingModel model) {
		List<ThingModelProperty> result = new ArrayList<>();
		Set<String> seenCanon = new HashSet<>();
		for (ThingModelProperty prop : model.getProperties().values()) {
			String canon = prop.getIdentifier() == null ? "" : prop.getIdentifier().toLowerCase();
			if (!seenCanon.add(canon)) {
				log.warn("[Tsdb] 物模型属性 canonical 名冲突，跳过后续属性 identifier={} canonical={}", prop.getIdentifier(), canon);
				continue;
			}
			result.add(prop);
		}
		return result;
	}

	/**
	 * 判定 TDengine「列不存在」错误：错误码集合 + 文本匹配双保险，遍历 nextException/cause 链。 无法可靠识别时返回
	 * false（保持原失败/DLQ 语义，不误判普通 SQL 错误）。
	 */
	static boolean isColumnMissing(SQLException e) {
		SQLException cur = e;
		while (cur != null) {
			if (cur.getErrorCode() != 0 && COLUMN_MISSING_CODES.contains(cur.getErrorCode())) {
				return true;
			}
			if (cur.getMessage() != null && containsAny(cur.getMessage(), COLUMN_MISSING_TEXTS)) {
				return true;
			}
			// 深入 cause 链（taos 驱动常把根因包装在 cause 的 SQLException 中）
			Throwable cause = cur.getCause();
			if (cause instanceof SQLException sqle) {
				SQLException c = sqle;
				while (c != null) {
					if (c.getErrorCode() != 0 && COLUMN_MISSING_CODES.contains(c.getErrorCode())) {
						return true;
					}
					if (c.getMessage() != null && containsAny(c.getMessage(), COLUMN_MISSING_TEXTS)) {
						return true;
					}
					c = c.getNextException();
				}
			}
			cur = cur.getNextException();
		}
		return false;
	}

	/** 判定「列已存在」类并发幂等错误（ALTER 重复 ADD 时容错为成功） */
	private static boolean isColumnAlreadyExists(SQLException e) {
		SQLException cur = e;
		while (cur != null) {
			if (cur.getMessage() != null) {
				String lower = cur.getMessage().toLowerCase();
				if (lower.contains("already exists") || lower.contains("duplicate column")
						|| lower.contains("column exists")) {
					return true;
				}
			}
			cur = cur.getNextException();
		}
		return false;
	}

	private static boolean containsAny(String msg, String[] texts) {
		String lower = msg.toLowerCase();
		for (String t : texts) {
			if (lower.contains(t)) {
				return true;
			}
		}
		return false;
	}

	/** 判定 TDengine「表不存在」错误：REST 错误码 9731 或 JDBC 0x2603 / 消息含 Table does not exist */
	private static boolean isTableMissing(SQLException e) {
		String msg = e.getMessage();
		return e.getErrorCode() == 9731 || e.getErrorCode() == 0x2603
				|| (msg != null && msg.contains("Table does not exist"));
	}

	private Connection getConnection() throws SQLException {
		Connection c = connection;
		if (c == null || c.isClosed()) {
			synchronized (this) {
				if (connection == null || connection.isClosed()) {
					Connection nc = DriverManager.getConnection(props.getJdbcUrl(), props.getJdbcUsername(),
							props.getJdbcPassword());
					nc.setAutoCommit(true);
					log.info("[Tsdb] TDengine 连接建立 url={}", props.getJdbcUrl());
					connection = nc;
				}
				return connection;
			}
		}
		return c;
	}

	private void closeQuietly() {
		Connection c = connection;
		connection = null;
		if (c != null) {
			try {
				c.close();
			}
			catch (SQLException ignore) {
				// 关闭异常可忽略
			}
		}
	}

	private static String abbreviate(String sql) {
		if (sql == null) {
			return "";
		}
		return sql.length() > 200 ? sql.substring(0, 200) + "…" : sql;
	}

}
