package com.energyx.tsdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TdengineQuerySqlBuilderTest {

	@Test
	void dataSql_asc_containsBacktickColumnsAndPlaceholders() {
		String sql = TdengineQuerySqlBuilder.buildDataSql("iot_tsdb_raw", "snd_ess_pcs", List.of("soc", "voltage"),
				"8000000000000000001", 1700000000000L, 1700003600000L, true, 1000, 0);
		assertTrue(sql.contains("SELECT ts, `soc`, `voltage`"));
		assertTrue(sql.contains("FROM iot_tsdb_raw.st_prop_snd_ess_pcs"));
		assertTrue(sql.contains("WHERE device_id = ? AND ts >= ? AND ts <= ?"));
		assertTrue(sql.contains("ORDER BY ts ASC"));
		assertTrue(sql.contains("LIMIT ? OFFSET ?"));
	}

	@Test
	void dataSql_desc() {
		String sql = TdengineQuerySqlBuilder.buildDataSql("iot_tsdb_raw", "snd_ess_pcs", List.of("temp"),
				"8000000000000000001", 1L, 2L, false, 20, 40);
		assertTrue(sql.contains("ORDER BY ts DESC"));
	}

	@Test
	void invalidProductKey_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> TdengineQuerySqlBuilder.buildDataSql("db", "bad key", List.of("soc"), "d1", 1L, 2L, true, 10, 0));
	}

	@Test
	void invalidIdentifier_throws() {
		assertThrows(IllegalArgumentException.class, () -> TdengineQuerySqlBuilder.buildDataSql("db", "pk",
				List.of("soc; drop table x"), "d1", 1L, 2L, true, 10, 0));
	}

	@Test
	void countSql() {
		String sql = TdengineQuerySqlBuilder.buildCountSql("iot_tsdb_raw", "snd_ess_pcs", "8000000000000000001", 1L,
				2L);
		assertTrue(sql.contains("SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs"));
		assertTrue(sql.contains("WHERE device_id = ? AND ts >= ? AND ts <= ?"));
	}

}
