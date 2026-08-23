package com.energyx.tsdb.writer;

import com.energyx.common.thingmodel.ThingModel;
import com.energyx.common.thingmodel.ThingModelParser;
import com.energyx.common.thingmodel.ThingModelResolver;
import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.service.TdengineQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TdengineWriter 缺列自动 ALTER / 建表 / 重试语义测试（M3.1）。
 *
 * <p>
 * 通过反射注入 mock Connection 规避 DriverManager 静态调用；SQLException 为模拟构造—— 缺列错误码 0x2602（JDBC
 * 消息内嵌）与 9730（REST 错误透传）已由 TDengine 3.3.1.0 实机验证，本测试用实机事实 构造并验证识别与流程逻辑（文本匹配兜底另测）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TdengineWriterTest {

	/** 物模型：soc(float, 已建列) + newProp(int, 缺失列) */
	private static final String SCHEMA = """
			{"properties":[
			  {"identifier":"soc","dataType":"float"},
			  {"identifier":"newProp","dataType":"int"}
			]}
			""";

	private static final String ALTER_PK1 = "ALTER STABLE iot_tsdb_raw.st_prop_pk1 ADD COLUMN `newprop` INT";

	@Mock
	ThingModelResolver resolver;

	@Mock
	TdengineQueryService queryService;

	TdengineWriter writer;

	TsdbProperties props;

	@BeforeEach
	void setUp() {
		props = new TsdbProperties();
		props.setRawDb("iot_tsdb_raw");
		writer = new TdengineWriter(props, resolver, queryService);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** 反射注入 mock Connection（connection 字段 volatile，绕过 DriverManager 静态调用） */
	private static void injectConnection(TdengineWriter w, Connection conn) throws Exception {
		Field f = TdengineWriter.class.getDeclaredField("connection");
		f.setAccessible(true);
		f.set(w, conn);
	}

	private static ThingModel model(String schema) {
		try {
			return ThingModelParser.parse(schema);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static SQLException colMissing(String msg) {
		return new SQLException(msg, null, 0x2602);
	}

	/** Statement：INSERT 首次抛缺列错误、后续成功；非 INSERT（DDL）返回 true */
	private static Statement statementFirstInsertThrows() throws Exception {
		Statement st = mock(Statement.class);
		AtomicInteger insertCalls = new AtomicInteger();
		when(st.execute(anyString())).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			if (sql.startsWith("INSERT") && insertCalls.getAndIncrement() == 0) {
				throw colMissing("Column does not exist: newProp");
			}
			return true;
		});
		return st;
	}

	/** Statement：INSERT 始终抛缺列错误（重试仍失败） */
	private static Statement statementInsertAlwaysThrows() throws Exception {
		Statement st = mock(Statement.class);
		when(st.execute(anyString())).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			if (sql.startsWith("INSERT")) {
				throw colMissing("Column does not exist: newProp");
			}
			return true;
		});
		return st;
	}

	private static Connection connection(Statement st) throws Exception {
		Connection conn = mock(Connection.class);
		when(conn.isClosed()).thenReturn(false);
		when(conn.createStatement()).thenReturn(st);
		return conn;
	}

	// ------------------------------------------------------------------
	// isColumnMissing 识别
	// ------------------------------------------------------------------

	@Test
	@DisplayName("isColumnMissing：错误码 0x2602 / 9730 识别（实机验证值）")
	void isColumnMissing_errorCode() {
		assertTrue(TdengineWriter.isColumnMissing(colMissing("some message")));
		assertTrue(TdengineWriter.isColumnMissing(new SQLException("some message", null, 9730)));
		// 实机捕获的真实消息形态（REST 透传 errorCode + JDBC 内嵌 0x2602 + desc 文本）
		assertTrue(TdengineWriter.isColumnMissing(new SQLException(
				"TDengine ERROR (0x2602): sql: INSERT ... (ts, msg_id, data_type, col1, col2) VALUES (now, 'm', "
						+ "'report', 1.0, 2), desc: Invalid column name: col2",
				null, 9730)));
	}

	@Test
	@DisplayName("isColumnMissing：文本识别")
	void isColumnMissing_text() {
		assertTrue(TdengineWriter.isColumnMissing(new SQLException("Column does not exist: newProp")));
		assertTrue(TdengineWriter.isColumnMissing(new SQLException("invalid column name newProp")));
		assertTrue(TdengineWriter.isColumnMissing(new SQLException("column not found")));
	}

	@Test
	@DisplayName("isColumnMissing：cause / nextException 链识别")
	void isColumnMissing_chain() {
		SQLException withCause = new SQLException("insert failed");
		withCause.initCause(new SQLException("Column not exist"));
		assertTrue(TdengineWriter.isColumnMissing(withCause));

		SQLException root = new SQLException("generic");
		root.setNextException(colMissing("unknown column x"));
		assertTrue(TdengineWriter.isColumnMissing(root));
	}

	@Test
	@DisplayName("isColumnMissing：普通 SQLException 不误判")
	void isColumnMissing_notMisjudged() {
		assertFalse(TdengineWriter.isColumnMissing(new SQLException("syntax error near 'select'")));
		assertFalse(TdengineWriter.isColumnMissing(new SQLException("Table does not exist: st_prop_x")));
	}

	// ------------------------------------------------------------------
	// execute：缺列 → ALTER → retry
	// ------------------------------------------------------------------

	@Test
	@DisplayName("缺列错误 → Resolver + DESCRIBE 差集 ALTER → 重试成功")
	void execute_columnMissing_altersAndRetries() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		Statement st = statementFirstInsertThrows();
		injectConnection(writer, connection(st));
		when(st.execute(ALTER_PK1)).thenReturn(true);

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newProp`) VALUES (1, 'm', 'report', 1.0, 2)");

		// INSERT 重试 1 次（首次抛 + 重试成功）+ ALTER 1 次 = 3 次 execute
		verify(st, times(3)).execute(anyString());
		verify(st).execute(ALTER_PK1);
		verify(queryService).propertyColumns("pk1");
		verify(queryService).invalidateColumnCache("pk1");
	}

	@Test
	@DisplayName("已存在列不重复 ADD（DESCRIBE 差集为空）")
	void execute_noMissingColumn_skipsAlter() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc", "newProp"));
		Statement st = statementFirstInsertThrows();
		injectConnection(writer, connection(st));

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newProp`) VALUES (1, 'm', 'report', 1.0, 2)");

		verify(st, never()).execute(startsWith("ALTER"));
		verify(queryService, never()).invalidateColumnCache(anyString());
	}

	@Test
	@DisplayName("ALTER 后 retry 仍失败 → 正常抛异常（ALTER 不再触发）")
	void execute_alterThenStillFails_throws() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		Statement st = statementInsertAlwaysThrows();
		injectConnection(writer, connection(st));
		when(st.execute(ALTER_PK1)).thenReturn(true);

		assertThrows(SQLException.class,
				() -> writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 "
						+ "TAGS ('1','','','pk1') (ts, msg_id, data_type, `soc`, `newProp`) "
						+ "VALUES (1, 'm', 'report', 1.0, 2)"));

		// ALTER 只执行 1 次（altered 置位后不再触发），最终抛异常进 DLQ
		verify(st, times(1)).execute(ALTER_PK1);
	}

	@Test
	@DisplayName("Resolver 返回 null → 不 ALTER → 正常失败")
	void execute_resolverNull_skipsAlterAndFails() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(null);
		Statement st = statementInsertAlwaysThrows();
		injectConnection(writer, connection(st));

		assertThrows(Exception.class,
				() -> writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 "
						+ "TAGS ('1','','','pk1') (ts, msg_id, data_type, `newProp`) VALUES (1, 'm', 'report', 2)"));

		verify(st, never()).execute(startsWith("ALTER"));
	}

	@Test
	@DisplayName("column already exists（并发幂等）→ 容错继续")
	void execute_alterAlreadyExists_tolerated() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		Statement st = statementFirstInsertThrows();
		injectConnection(writer, connection(st));
		when(st.execute(ALTER_PK1)).thenThrow(new SQLException("Column already exists: newProp"));

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newProp`) VALUES (1, 'm', 'report', 1.0, 2)");

		// already-exists 视为成功 → 重试 INSERT 成功
		verify(st, times(3)).execute(anyString());
		verify(queryService).invalidateColumnCache("pk1");
	}

	// ------------------------------------------------------------------
	// 建表分支与多 stable
	// ------------------------------------------------------------------

	@Test
	@DisplayName("table missing 仍走 autoCreateStables 分支（类型按物模型映射）")
	void execute_tableMissing_autoCreateWithMappedTypes() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		Statement st = mock(Statement.class);
		AtomicInteger insertCalls = new AtomicInteger();
		when(st.execute(anyString())).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			if (sql.startsWith("INSERT") && insertCalls.getAndIncrement() == 0) {
				throw new SQLException("Table does not exist: st_prop_pk1", null, 9731);
			}
			return true; // CREATE STABLE 与重试 INSERT 成功
		});
		injectConnection(writer, connection(st));

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`) VALUES (1, 'm', 'report', 1.0)");

		verify(st, times(3)).execute(anyString()); // INSERT 首次抛 + CREATE + INSERT 重试
		verify(st).execute(startsWith("CREATE STABLE IF NOT EXISTS iot_tsdb_raw.st_prop_pk1"));
		verify(st).execute(org.mockito.ArgumentMatchers.contains("`newprop` INT")); // 类型按物模型映射（列名小写）
	}

	@Test
	@DisplayName("batch 含多个 productKey/stable → 逐个 ALTER 缺列")
	void execute_batchMultipleStables_altersEach() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(resolver.resolve("pk2")).thenReturn(model(SCHEMA2));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		when(queryService.propertyColumns("pk2")).thenReturn(Set.of("temp"));
		Statement st = statementFirstInsertThrows();
		injectConnection(writer, connection(st));
		when(st.execute(ALTER_PK1)).thenReturn(true);
		when(st.execute("ALTER STABLE iot_tsdb_raw.st_prop_pk2 ADD COLUMN `newprop` INT")).thenReturn(true);

		String batch = "INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newProp`) VALUES (1, 'm', 'report', 1.0, 2) "
				+ "dev_2 USING iot_tsdb_raw.st_prop_pk2 TAGS ('2','','','pk2') "
				+ "(ts, msg_id, data_type, `temp`, `newProp`) VALUES (2, 'm2', 'report', 25.0, 3)";
		writer.execute(batch);

		verify(st).execute(ALTER_PK1);
		verify(st).execute("ALTER STABLE iot_tsdb_raw.st_prop_pk2 ADD COLUMN `newprop` INT");
		verify(queryService).invalidateColumnCache("pk1");
		verify(queryService).invalidateColumnCache("pk2");
	}

	private static final String SCHEMA2 = """
			{"properties":[
			  {"identifier":"temp","dataType":"float"},
			  {"identifier":"newProp","dataType":"int"}
			]}
			""";

	@Test
	@DisplayName("batch 多 stable 中一个模型缺失 → 该 stable 不 ALTER，整体失败")
	void execute_batchModelMissing_fails() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA));
		when(resolver.resolve("pk2")).thenReturn(null);
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		Statement st = statementInsertAlwaysThrows();
		injectConnection(writer, connection(st));
		when(st.execute(ALTER_PK1)).thenReturn(true);

		String batch = "INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newProp`) VALUES (1, 'm', 'report', 1.0, 2) "
				+ "dev_2 USING iot_tsdb_raw.st_prop_pk2 TAGS ('2','','','pk2') "
				+ "(ts, msg_id, data_type, `newProp`) VALUES (2, 'm2', 'report', 3)";

		assertThrows(SQLException.class, () -> writer.execute(batch));

		// pk1 正常 ALTER；模型缺失的 pk2 不 ALTER；整体最终失败进 DLQ
		verify(st).execute(ALTER_PK1);
		verify(st, never()).execute("ALTER STABLE iot_tsdb_raw.st_prop_pk2 ADD COLUMN `newprop` INT");
	}

	@Test
	@DisplayName("其他普通 SQLException → 不触发 ALTER/建表/Resolver")
	void execute_genericError_noAlter() throws Exception {
		Statement st = mock(Statement.class);
		when(st.execute(anyString())).thenThrow(new SQLException("syntax error"));
		injectConnection(writer, connection(st));

		assertThrows(SQLException.class,
				() -> writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 "
						+ "TAGS ('1','','','pk1') (ts, msg_id, data_type, `soc`) VALUES (1, 'm', 'report', 1.0)"));

		verify(resolver, never()).resolve(anyString());
		verify(st, never()).execute(startsWith("ALTER"));
	}

	@Test
	@DisplayName("Feign/fetch 异常 → 不 ALTER → 正常失败")
	void execute_fetchThrows_fails() throws Exception {
		when(resolver.resolve("pk1")).thenThrow(new IllegalStateException("feign down"));
		Statement st = statementInsertAlwaysThrows();
		injectConnection(writer, connection(st));

		assertThrows(Exception.class,
				() -> writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 "
						+ "TAGS ('1','','','pk1') (ts, msg_id, data_type, `newProp`) VALUES (1, 'm', 'report', 2)"));

		verify(st, never()).execute(startsWith("ALTER"));
	}

	// ------------------------------------------------------------------
	// M3.1.5：canonical 列名冲突防御 / 逐列 ALTER / 实机错误码
	// ------------------------------------------------------------------

	/** 物模型：foo + Foo（canonical 冲突，折叠后均为 foo） */
	private static final String COLLISION_SCHEMA = """
			{"properties":[
			  {"identifier":"foo","dataType":"float"},
			  {"identifier":"Foo","dataType":"int"}
			]}
			""";

	/** 物模型：foo + bar（无冲突） */
	private static final String NORMAL_SCHEMA = """
			{"properties":[
			  {"identifier":"foo","dataType":"float"},
			  {"identifier":"bar","dataType":"float"}
			]}
			""";

	/** 物模型：soc 已建列 + newA/newB 两个缺失列（逐列 ALTER 场景） */
	private static final String SCHEMA3 = """
			{"properties":[
			  {"identifier":"soc","dataType":"float"},
			  {"identifier":"newA","dataType":"int"},
			  {"identifier":"newB","dataType":"string"}
			]}
			""";

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) >= 0) {
			count++;
			idx += needle.length();
		}
		return count;
	}

	/** 从 mock Statement 捕获的 execute 调用中取建表语句 */
	private static String captureCreateSql(Statement st, int totalCalls) throws Exception {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(st, org.mockito.Mockito.times(totalCalls)).execute(captor.capture());
		return captor.getAllValues().stream().filter(s -> s.startsWith("CREATE")).findFirst().orElseThrow();
	}

	@Test
	@DisplayName("建表：canonical 冲突（foo+Foo）只建首个列，不生成重复物理列")
	void execute_tableMissing_canonicalCollisionSkipsDuplicate() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(COLLISION_SCHEMA));
		Statement st = mock(Statement.class);
		AtomicInteger insertCalls = new AtomicInteger();
		when(st.execute(anyString())).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			if (sql.startsWith("INSERT") && insertCalls.getAndIncrement() == 0) {
				throw new SQLException("Table does not exist: st_prop_pk1", null, 9731);
			}
			return true;
		});
		injectConnection(writer, connection(st));

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `foo`, `Foo`) VALUES (1, 'm', 'report', 1.0, 2)");

		String create = captureCreateSql(st, 3); // INSERT 抛 + CREATE + INSERT 重试
		assertTrue(create.contains("`foo` FLOAT"), create);
		assertEquals(1, countOccurrences(create, "`foo`"), create); // 不生成第二个 foo 物理列
	}

	@Test
	@DisplayName("建表：正常 foo + bar 各自生成一列（非冲突行为不变）")
	void execute_tableMissing_noCollision_buildsBothColumns() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(NORMAL_SCHEMA));
		Statement st = mock(Statement.class);
		AtomicInteger insertCalls = new AtomicInteger();
		when(st.execute(anyString())).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			if (sql.startsWith("INSERT") && insertCalls.getAndIncrement() == 0) {
				throw new SQLException("Table does not exist: st_prop_pk1", null, 9731);
			}
			return true;
		});
		injectConnection(writer, connection(st));

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `foo`, `bar`) VALUES (1, 'm', 'report', 1.0, 2.0)");

		String create = captureCreateSql(st, 3);
		assertTrue(create.contains("`foo` FLOAT"), create);
		assertTrue(create.contains("`bar` FLOAT"), create);
	}

	@Test
	@DisplayName("单 stable 多缺列 → 逐列 ALTER（每列一次，共 2 次）")
	void execute_singleStableMultipleMissing_altersEachColumn() throws Exception {
		when(resolver.resolve("pk1")).thenReturn(model(SCHEMA3));
		when(queryService.propertyColumns("pk1")).thenReturn(Set.of("soc"));
		Statement st = statementFirstInsertThrows();
		injectConnection(writer, connection(st));
		when(st.execute("ALTER STABLE iot_tsdb_raw.st_prop_pk1 ADD COLUMN `newa` INT")).thenReturn(true);
		when(st.execute("ALTER STABLE iot_tsdb_raw.st_prop_pk1 ADD COLUMN `newb` NCHAR(64)")).thenReturn(true);

		writer.execute("INSERT INTO iot_tsdb_raw.dev_1 USING iot_tsdb_raw.st_prop_pk1 TAGS ('1','','','pk1') "
				+ "(ts, msg_id, data_type, `soc`, `newA`, `newB`) VALUES (1, 'm', 'report', 1.0, 2, 'x')");

		// INSERT 首次抛 + 2×单列 ALTER + INSERT 重试成功 = 4 次 execute
		verify(st, org.mockito.Mockito.times(4)).execute(anyString());
		verify(st).execute("ALTER STABLE iot_tsdb_raw.st_prop_pk1 ADD COLUMN `newa` INT");
		verify(st).execute("ALTER STABLE iot_tsdb_raw.st_prop_pk1 ADD COLUMN `newb` NCHAR(64)");
		verify(queryService).invalidateColumnCache("pk1");
	}

}
