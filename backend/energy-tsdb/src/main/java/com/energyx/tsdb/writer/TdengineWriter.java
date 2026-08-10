package com.energyx.tsdb.writer;

import com.energyx.tsdb.config.TsdbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * TDengine JDBC 写入器（taos-jdbcdriver / TAOS-RS 连接）。
 *
 * <p>
 * 单连接 + 整语句批量执行：TDengine 原生支持一条语句内多个 INSERT 块 （自动建子表 + 多行多列），写入吞吐由批量缓冲的行数/字节阈值控制。
 * 连接失效时重建后重试一次，仍失败则上抛交由消费端进 DLQ。
 * </p>
 *
 * <p>
 * 连接为进程级单例：TAOS-RS 连接可复用，写放大远小于 MySQL；后续若需 更高吞吐可换 taos-spring-boot-starter 连接池，当前以简单可靠优先。
 * </p>
 */
@Slf4j
@Component
public class TdengineWriter implements TsdbWriter {

	private final TsdbProperties props;

	private volatile Connection connection;

	public TdengineWriter(TsdbProperties props) {
		this.props = props;
	}

	@Override
	public void execute(String sql) throws Exception {
		int attempt = 0;
		while (true) {
			try {
				Connection conn = getConnection();
				try (Statement st = conn.createStatement()) {
					st.execute(sql);
				}
				return;
			}
			catch (SQLException e) {
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
