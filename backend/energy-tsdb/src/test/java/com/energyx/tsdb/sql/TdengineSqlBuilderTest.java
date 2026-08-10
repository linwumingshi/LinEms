package com.energyx.tsdb.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.common.message.ThingPropertyMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

}
