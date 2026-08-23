package com.energyx.tsdb.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDengine 写入 SQL 构造测试：对齐 sql/tdengine/10_stable.sql 的宽表/事件建模。
 */
class TdengineSqlBuilderTest {

	private final ObjectMapper om = new ObjectMapper();

	private ThingPropertyMessage propMsg() {
		ThingPropertyMessage m = new ThingPropertyMessage();
		m.setMessageId("m-1");
		m.setDeviceId(1100000000000000001L);
		m.setStationId(10001L);
		m.setEnterpriseId(1L);
		m.setProductKey("snd_ess_pcs");
		m.setDataType("report");
		m.setTs(1722859200000L);
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("soc", 85.2);
		props.put("run_mode", 2);
		m.setProperties(props);
		return m;
	}

	@Test
	void buildPropertyInsert_shouldEmitWideTableColumnsInModelOrder() {
		String sql = TdengineSqlBuilder.buildPropertyInsert(propMsg(), "iot_tsdb_raw", om);

		assertTrue(sql.startsWith(
				"INSERT INTO iot_tsdb_raw.dev_1100000000000000001 " + "USING iot_tsdb_raw.st_prop_snd_ess_pcs TAGS ("),
				sql);
		assertTrue(sql.contains("'1100000000000000001', '10001', '1', 'snd_ess_pcs'"), sql);
		assertTrue(sql.contains("(ts, msg_id, data_type, `soc`, `run_mode`)"), sql);
		assertTrue(sql.contains("VALUES (1722859200000, 'm-1', 'report', 85.2, 2)"), sql);
	}

	@Test
	void buildPropertyInsert_shouldSkipUnsafeColumnName() {
		ThingPropertyMessage m = propMsg();
		m.getProperties().put("bad column!", 1);
		m.getProperties().put("soc", 90.0);
		String sql = TdengineSqlBuilder.buildPropertyInsert(m, "iot_tsdb_raw", om);
		assertFalse(sql.contains("bad column!"), sql);
		assertTrue(sql.contains("`soc`"), sql);
	}

	@Test
	void buildPropertyInsert_shouldEscapeStringAndRenderBoolNull() {
		ThingPropertyMessage m = propMsg();
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("alarm", true);
		props.put("unit_no", "柜-1'号");
		props.put("ghost", null);
		m.setProperties(props);
		String sql = TdengineSqlBuilder.buildPropertyInsert(m, "iot_tsdb_raw", om);
		assertTrue(sql.contains("`alarm`"), sql);
		assertTrue(sql.contains("true"), sql);
		assertTrue(sql.contains("'柜-1\\'号'"), sql);
		assertTrue(sql.contains("`ghost`"), sql);
		assertTrue(sql.contains("NULL"), sql);
	}

	@Test
	void buildPropertyInsert_shouldRejectMissingDeviceOrBadProductKey() {
		ThingPropertyMessage noDevice = propMsg();
		noDevice.setDeviceId(null);
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildPropertyInsert(noDevice, "iot_tsdb_raw", om));

		ThingPropertyMessage badPk = propMsg();
		badPk.setProductKey("bad pk!");
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildPropertyInsert(badPk, "iot_tsdb_raw", om));
	}

	@Test
	void buildEventInsert_shouldEmitEventColumnsWithJsonPayload() {
		ThingEventMessage e = new ThingEventMessage();
		e.setMessageId("evt-1");
		e.setEventId("evt-inst-1");
		e.setDeviceId(1100000000000000001L);
		e.setStationId(10001L);
		e.setEnterpriseId(1L);
		e.setProductKey("snd_ess_pcs");
		e.setEventName("overTemp");
		e.setSeverity(3);
		e.setCode("ALM_TEMP_HIGH");
		e.setTs(1722859260000L);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("temp", 61.5);
		data.put("cellNo", "B12");
		e.setData(data);

		String sql = TdengineSqlBuilder.buildEventInsert(e, "iot_tsdb_event", om);

		assertTrue(sql.startsWith(
				"INSERT INTO iot_tsdb_event.dev_1100000000000000001_evt " + "USING iot_tsdb_event.st_event TAGS ("),
				sql);
		assertTrue(sql.contains("(ts, event_id, event_name, severity, code, payload)"), sql);
		assertTrue(sql.contains("VALUES (1722859260000, 'evt-inst-1', 'overTemp', 3, "
				+ "'ALM_TEMP_HIGH', '{\"temp\":61.5,\"cellNo\":\"B12\"}')"), sql);
	}

	@Test
	void buildEventInsert_emptyPayload_shouldEmitNull() {
		ThingEventMessage e = new ThingEventMessage();
		e.setMessageId("evt-2");
		e.setEventId("evt-inst-2");
		e.setDeviceId(1100000000000000001L);
		e.setProductKey("snd_ess_pcs");
		e.setEventName("heartbeat");
		e.setSeverity(1);
		e.setTs(1722859320000L);
		String sql = TdengineSqlBuilder.buildEventInsert(e, "iot_tsdb_event", om);
		assertTrue(sql.endsWith("'heartbeat', 1, NULL, NULL)"), sql);
	}

	@Test
	void tsLiteral_shouldBeEpochMillis() {
		assertEquals("1722859200000", TdengineSqlBuilder.tsLiteral(1722859200000L));
	}

	@Test
	void strLiteral_shouldEscapeBackslashAndQuote() {
		assertEquals("'it\\'s'", TdengineSqlBuilder.strLiteral("it's"));
		assertEquals("'a\\\\b'", TdengineSqlBuilder.strLiteral("a\\b"));
		assertEquals("NULL", TdengineSqlBuilder.strLiteral(null));
	}

	@Test
	void buildCreatePropertyStable_shouldEmitPublicColumnsPlusPropsAndTags() {
		java.util.Map<String, String> cols = new java.util.LinkedHashMap<>();
		cols.put("importPower", "FLOAT");
		cols.put("temp", "FLOAT");
		String ddl = TdengineSqlBuilder.buildCreatePropertyStable("snd_ess_meter", "iot_tsdb_raw", cols);

		assertTrue(ddl.startsWith("CREATE STABLE IF NOT EXISTS iot_tsdb_raw.st_prop_snd_ess_meter ("), ddl);
		assertTrue(ddl.contains("ts TIMESTAMP, msg_id NCHAR(64), data_type NCHAR(16)"), ddl);
		// TDengine 列名一律小写化（实机验证）
		assertTrue(ddl.contains("`importpower` FLOAT"), ddl);
		assertTrue(ddl.contains("`temp` FLOAT"), ddl);
		assertTrue(ddl.endsWith("TAGS (device_id NCHAR(64), station_id NCHAR(32), enterprise_id NCHAR(32), "
				+ "product_key NCHAR(64))"), ddl);
	}

	// ------------------------------------------------------------------
	// M3.1：dataType → TDengine 类型映射 / ALTER 语句
	// ------------------------------------------------------------------

	@Test
	void columnType_shouldMapAllThingModelDataTypes() {
		assertEquals("FLOAT", TdengineSqlBuilder.columnType("float"));
		assertEquals("FLOAT", TdengineSqlBuilder.columnType("double"));
		assertEquals("FLOAT", TdengineSqlBuilder.columnType("decimal"));
		assertEquals("FLOAT", TdengineSqlBuilder.columnType("number"));
		assertEquals("INT", TdengineSqlBuilder.columnType("int"));
		assertEquals("INT", TdengineSqlBuilder.columnType("integer"));
		assertEquals("INT", TdengineSqlBuilder.columnType("byte"));
		assertEquals("INT", TdengineSqlBuilder.columnType("short"));
		assertEquals("BIGINT", TdengineSqlBuilder.columnType("long"));
		assertEquals("BIGINT", TdengineSqlBuilder.columnType("enum"));
		assertEquals("BOOL", TdengineSqlBuilder.columnType("bool"));
		assertEquals("BOOL", TdengineSqlBuilder.columnType("boolean"));
		assertEquals("NCHAR(64)", TdengineSqlBuilder.columnType("string"));
		assertEquals("NCHAR(64)", TdengineSqlBuilder.columnType("text"));
		assertEquals("NCHAR(64)", TdengineSqlBuilder.columnType("date"));
		assertEquals("NCHAR(64)", TdengineSqlBuilder.columnType("time"));
		assertEquals("NCHAR(1024)", TdengineSqlBuilder.columnType("struct"));
		assertEquals("NCHAR(1024)", TdengineSqlBuilder.columnType("array"));
		assertEquals("NCHAR(1024)", TdengineSqlBuilder.columnType("unknown-type"));
		assertEquals("NCHAR(1024)", TdengineSqlBuilder.columnType(null));
	}

	@Test
	void buildCreatePropertyStable_shouldUseMappedTypes() {
		java.util.Map<String, String> cols = new java.util.LinkedHashMap<>();
		cols.put("soc", TdengineSqlBuilder.columnType("float"));
		cols.put("runMode", TdengineSqlBuilder.columnType("enum"));
		cols.put("alarm", TdengineSqlBuilder.columnType("bool"));
		cols.put("unitNo", TdengineSqlBuilder.columnType("string"));
		cols.put("cells", TdengineSqlBuilder.columnType("array"));
		String ddl = TdengineSqlBuilder.buildCreatePropertyStable("snd_ess_pcs", "iot_tsdb_raw", cols);

		assertTrue(ddl.contains("`soc` FLOAT"), ddl);
		// TDengine 列名一律小写化（实机验证）
		assertTrue(ddl.contains("`runmode` BIGINT"), ddl);
		assertTrue(ddl.contains("`alarm` BOOL"), ddl);
		assertTrue(ddl.contains("`unitno` NCHAR(64)"), ddl);
		assertTrue(ddl.contains("`cells` NCHAR(1024)"), ddl);
	}

	@Test
	void buildAlterStableSql_shouldEmitSingleColumnLowercased() {
		String ddl = TdengineSqlBuilder.buildAlterStableSql("iot_tsdb_raw", "st_prop_snd_ess_pcs", "newProp", "FLOAT");
		assertEquals("ALTER STABLE iot_tsdb_raw.st_prop_snd_ess_pcs ADD COLUMN `newprop` FLOAT", ddl);
	}

	@Test
	void buildAlterStableSql_shouldRejectUnsafeStableOrColumn() {
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildAlterStableSql("db", "not_stable", "x", "FLOAT"));
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildAlterStableSql("db", "st_prop_bad pk!", "x", "FLOAT"));
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildAlterStableSql("db", "st_prop_pk", "bad column!", "FLOAT"));
		assertThrows(IllegalArgumentException.class,
				() -> TdengineSqlBuilder.buildAlterStableSql("db", "st_prop_pk", "ok", ""));
	}

	@Test
	void extractPropertyStables_shouldParseStableAndColumnsFromBatch() {
		String insert = "INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_testMeter TAGS ('1','','','testMeter') "
				+ "(ts, msg_id, data_type, `importPower`, `temp`) VALUES (1722859200000, 'm1', 'report', 123.0, 25.5)\n"
				+ "INSERT INTO iot_tsdb_raw.dev_2 USING iot_tsdb_raw.st_prop_testMeter TAGS ('2','','','testMeter') "
				+ "(ts, msg_id, data_type, `importPower`) VALUES (1722859200001, 'm2', 'report', 88.0)";

		java.util.Map<String, java.util.Set<String>> stables = TdengineSqlBuilder.extractPropertyStables(insert,
				"iot_tsdb_raw");

		assertEquals(1, stables.size());
		java.util.Set<String> cols = stables.get("st_prop_testMeter");
		assertNotNull(cols);
		// 公共列被剔除，两行 INSERT 的属性列并集
		assertEquals(java.util.Set.of("importPower", "temp"), cols);
	}

	@Test
	void extractPropertyStables_emptyOrNoInsert_shouldReturnEmpty() {
		assertTrue(TdengineSqlBuilder.extractPropertyStables(null, "iot_tsdb_raw").isEmpty());
		assertTrue(TdengineSqlBuilder.extractPropertyStables("SELECT 1", "iot_tsdb_raw").isEmpty());
	}

}
