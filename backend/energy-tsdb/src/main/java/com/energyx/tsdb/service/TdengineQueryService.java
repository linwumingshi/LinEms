package com.energyx.tsdb.service;

import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.sql.TdengineQuerySqlBuilder;
import com.energyx.tsdb.sql.TdengineSqlBuilder;
import com.energyx.tsdb.web.dto.PropertyHistoryRecord;
import com.energyx.tsdb.web.dto.PropertyHistoryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TDengine 属性历史查询。
 *
 * <p>
 * 连接照 {@code TdengineWriter} 的进程级单连接模式（懒初始化 + 失效重建重试一次）； 查询用 PreparedStatement 绑定
 * {@code ?}（device_id 字符串、ts 毫秒 long、offset/limit int）。 属性列白名单 = {@code DESCRIBE}
 * 结果剔除公共列(ts/msg_id/data_type)与 TAG 行，缓存 60s。
 * </p>
 */
@Slf4j
@Service
public class TdengineQueryService {

	private static final Set<String> COMMON_COLUMNS = Set.of("ts", "msg_id", "data_type");

	private static final long WHITELIST_TTL_MS = 60_000L;

	private final TsdbProperties props;

	private volatile Connection connection;

	private final Map<String, Set<String>> columnCache = new ConcurrentHashMap<>();

	private final Map<String, Long> columnCacheLoadedAt = new ConcurrentHashMap<>();

	public TdengineQueryService(TsdbProperties props) {
		this.props = props;
	}

	/** 查询属性历史：data + count 各一条 PreparedStatement；白名单过滤后的 identifiers 为空 → 抛参数异常。 */
	public PropertyHistoryView queryHistory(String deviceId, String productKey, List<String> identifiers,
			long startTime, long endTime, boolean asc, int page, int size) throws SQLException {
		if (!TdengineSqlBuilder.isSafeKey(productKey)) {
			throw new IllegalArgumentException("productKey 非法: " + productKey);
		}
		Set<String> whitelist = columnWhitelist(productKey);
		// TDengine 列名大小写不敏感（DESCRIBE 返回小写），匹配用小写；selected 保留请求原始大小写（返回给前端）
		Set<String> lowerWhitelist = whitelist.stream().map(String::toLowerCase).collect(Collectors.toSet());
		List<String> selected = identifiers.stream()
			.filter(id -> lowerWhitelist.contains(id.toLowerCase()))
			.distinct()
			.toList();
		if (selected.isEmpty()) {
			throw new IllegalArgumentException("请求的属性均不在该产品物模型中");
		}

		// (page-1)*size 可能 int 溢出为负 → long 计算并对超范围 offset 抛参数异常（controller 转 400）
		long longOffset = (long) (page - 1) * size;
		if (longOffset > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("分页 offset 超出范围");
		}
		int offset = (int) longOffset;
		String dataSql = TdengineQuerySqlBuilder.buildDataSql(props.getRawDb(), productKey, selected, deviceId,
				startTime, endTime, asc, size, offset);
		String countSql = TdengineQuerySqlBuilder.buildCountSql(props.getRawDb(), productKey, deviceId, startTime,
				endTime);

		int attempt = 0;
		while (true) {
			try {
				Connection conn = getConnection();
				long total;
				try (PreparedStatement countPs = conn.prepareStatement(countSql)) {
					countPs.setString(1, deviceId);
					countPs.setLong(2, startTime);
					countPs.setLong(3, endTime);
					try (ResultSet rs = countPs.executeQuery()) {
						rs.next();
						total = rs.getLong(1);
					}
				}
				List<PropertyHistoryRecord> records = new ArrayList<>();
				try (PreparedStatement dataPs = conn.prepareStatement(dataSql)) {
					dataPs.setString(1, deviceId);
					dataPs.setLong(2, startTime);
					dataPs.setLong(3, endTime);
					dataPs.setInt(4, size);
					dataPs.setInt(5, offset);
					try (ResultSet rs = dataPs.executeQuery()) {
						while (rs.next()) {
							PropertyHistoryRecord rec = new PropertyHistoryRecord();
							rec.setTs(rs.getTimestamp("ts").getTime());
							Map<String, Object> values = new LinkedHashMap<>();
							for (String id : selected) {
								Object v = rs.getObject(id);
								if (!rs.wasNull()) {
									values.put(id, v);
								}
							}
							rec.setValues(values);
							records.add(rec);
						}
					}
				}
				PropertyHistoryView view = new PropertyHistoryView();
				view.setDeviceId(deviceId);
				view.setProductKey(productKey);
				view.setTotal(total);
				view.setRecords(records);
				return view;
			}
			catch (SQLException e) {
				attempt++;
				closeQuietly();
				if (attempt >= 2) {
					log.error("[Tsdb] 历史查询失败（已重试） deviceId={} errorCode={}", deviceId, e.getErrorCode(), e);
					throw e;
				}
				log.warn("[Tsdb] 历史查询异常，重建连接重试 deviceId={} errorCode={}", deviceId, e.getErrorCode(), e);
			}
		}
	}

	/** DESCRIBE 属性列白名单：剔除公共列与 TAG 行，结果缓存 WHITELIST_TTL_MS。 */
	private Set<String> columnWhitelist(String productKey) throws SQLException {
		long now = System.currentTimeMillis();
		Set<String> cached = columnCache.get(productKey);
		Long loadedAt = columnCacheLoadedAt.get(productKey);
		if (cached != null && loadedAt != null && now - loadedAt < WHITELIST_TTL_MS) {
			return cached;
		}
		Set<String> whitelist = new LinkedHashSet<>();
		String sql = "DESCRIBE " + props.getRawDb() + ".st_prop_" + productKey;
		int attempt = 0;
		while (true) {
			try {
				Connection conn = getConnection();
				try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
					while (rs.next()) {
						// DESCRIBE 行：1=field 2=type 3=length 4=note（TAG 行 note="TAG"）
						String field = rs.getString(1);
						String note = rs.getString(4);
						if ("TAG".equals(note) || COMMON_COLUMNS.contains(field)) {
							continue;
						}
						if (field != null && !field.isBlank()) {
							whitelist.add(field);
						}
					}
				}
				break;
			}
			catch (SQLException e) {
				// 表不存在（TDengine REST 9731 / JDBC 0x2603）：该产品尚无任何数据落库，
				// 视为「无属性列」而非查询错误——上层据此返回空结果而非 500
				if (isTableMissing(e)) {
					log.info("[Tsdb] 超级表不存在视为空数据 productKey={}", productKey);
					columnCache.put(productKey, whitelist);
					columnCacheLoadedAt.put(productKey, now);
					return whitelist;
				}
				attempt++;
				closeQuietly();
				if (attempt >= 2) {
					throw e;
				}
				log.warn("[Tsdb] DESCRIBE 异常，重建连接重试 errorCode={}", e.getErrorCode(), e);
			}
		}
		columnCache.put(productKey, whitelist);
		columnCacheLoadedAt.put(productKey, now);
		return whitelist;
	}

	/**
	 * 产品属性超级表当前属性列集合（M3.1：供自动 ALTER 差集计算复用）。 复用 {@link #columnWhitelist} 的 DESCRIBE + 60s
	 * 缓存语义。
	 * @param productKey 产品标识
	 * @return 当前属性列集合（不含公共列与 TAG）；表不存在视为空集
	 * @throws SQLException DESCRIBE 失败（非表不存在）
	 */
	public Set<String> propertyColumns(String productKey) throws SQLException {
		return columnWhitelist(productKey);
	}

	/** 失效指定产品的列集缓存（M3.1：ALTER 成功后调用，下次 DESCRIBE 重新拉取新列集） */
	public void invalidateColumnCache(String productKey) {
		columnCache.remove(productKey);
		columnCacheLoadedAt.remove(productKey);
	}

	/** 判定 TDengine「表不存在」错误：REST 错误码 9731 或 JDBC 0x2603 / 消息含 Table does not exist */
	private boolean isTableMissing(SQLException e) {
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
					log.info("[Tsdb] TDengine 查询连接建立 url={}", props.getJdbcUrl());
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

}
