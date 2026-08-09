# 设备数据中心（子项目 B）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 设备详情抽屉新增「运行状态/物模型」Tab：最新值卡片（影子 reported）+ 历史值查询（TDengine 折线图 + 分页表格），并修复 TDengine `run_mode`/`runMode` 列名不一致导致的当日数据丢失根因。

**Architecture:** 前端为聚合层，一次详情打开发 3 类请求（`GET /api/shadow/{deviceId}`、`GET /api/product/thing-model/by-key?productKey=`、`GET /api/tsdb/property/history`）。后端新增两条读路径：energy-product 加 TSL by-key 入口（设备只有 productKey 无 productId）；energy-tsdb 新增纯 SQL 构建器 + 查询 service（直连 TAOS-RS，无 MySQL）+ controller，网关加 `/api/tsdb/**` 路由（StripPrefix=1）。TDengine 侧先 `ALTER STABLE ADD COLUMN runMode` 对齐写路径，再造分钟级数据供读接口与冒烟验证。

**Tech Stack:** Spring Boot 3.5（energy-product / energy-tsdb）、MyBatis-Plus 3.x、taos-jdbcdriver 3.9.0（TAOS-RS）、TDengine 3.3.1 单节点容器（`ems-tdengine`）、Nacos 配置中心；前端 Vue3 + Element Plus + ECharts + Vitest + vue-tsc；浏览器冒烟 playwright-core + Edge 无头。

## Global Constraints

（源：spec §11，实施时逐条遵守）

- **提交红线**：不 `git add -A`，一律显式 `:/路径` pathspec；绝不提交 `backend/energy-mqtt-broker/.../BrokerProperties.java` 与 `frontend/vite.config.ts`（本机专属，始终 M）。
- **网关路由约定**：新增 `/api/tsdb/**` StripPrefix=1，controller 映射 `/tsdb`（不带 `/api`）。
- **雪花 Long → string**：JacksonConfig 装箱 Long→ToStringSerializer；**primitive long 保持数字**（`ts`/`total` 用 primitive）。
- **前端 id 均为 string**（deviceId/productId）。
- **ObjectMapper FAIL_ON_UNKNOWN off**：历史查询全部走 query 参数，无需新增 request body DTO。
- **productKey 可含 `_`**（如 `snd_ess_pcs`）；列名 = TSL identifier 原样，反引号包裹 + DESCRIBE 白名单，禁止拼接未校验列名。
- **TDengine 单节点 REPLICA 1**（生产集群 REPLICA 2，见 `sql/tdengine/00_database.sql`）。
- 界面文案中文；前端验证命令在 `frontend/` 目录跑（`cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vue-tsc --noEmit`、`npm test`）。
- 服务与 TDengine 均为本机原生/容器运行（vite :25173、网关 :8000、TDengine :6041）；Nacos 须先起。
- 异常约定：controller 参数校验抛 `BusinessException(ErrorCode.PARAM_INVALID, msg)`（GlobalExceptionHandler 映射为 400 Result）；不抛裸 `ValidationException`（会落 `Throwable`→500）。

---

### Task 1: TDengine 列修复（runMode）+ 造数

修复线上 stable 缺 `runMode` 列导致写路径拒收的当日数据丢失；修正 DDL 模板；造分钟级数据供读接口与冒烟。

**Files:**
- Modify: `sql/tdengine/10_stable.sql:25`
- Modify: `sql/tdengine/20_sample_stable.sql:20,68`
- Create: `sql/tdengine/seed_props.sh`

**Interfaces:**
- Consumes: `iot_tsdb_raw.st_prop_snd_ess_pcs`（已存在，含 `run_mode` 列）、子表 `dev_8000000000000000001`（已有 2 行）。
- Produces: 线上 stable 增加 `runMode INT` 列；`st_prop_snd_ess_pcs` 中该设备约 29 行分钟级点（含 `runMode` 非空）；DDL 模板与写路径列名一致。

- [ ] **Step 1: 为线上 stable 补 `runMode` 列**

```bash
curl -s -u root:taosdata -d 'ALTER STABLE iot_tsdb_raw.st_prop_snd_ess_pcs ADD COLUMN runMode INT' http://127.0.0.1:6041/rest/sql
```

Expected: 返回 `{"code":0,...}`。

- [ ] **Step 2: 验证 DESCRIBE 出现 runMode（且 run_mode 旧列保留）**

```bash
curl -s -u root:taosdata -d 'DESCRIBE iot_tsdb_raw.st_prop_snd_ess_pcs' http://127.0.0.1:6041/rest/sql
```

Expected: data 数组含 `["runMode","INT",4,"",...]` 与 `["run_mode","INT",4,"",...]` 两行；TAG 行 `note` 为 `"TAG"`。

- [ ] **Step 3: 修正 DDL 模板 `run_mode` → `runMode`**

`sql/tdengine/10_stable.sql:25`：

```sql
  temp      FLOAT,
  runMode   INT
```

`sql/tdengine/20_sample_stable.sql:20`（列定义同样改 `runMode`）；`:68` 连续查询条件 `WHERE run_mode IS NOT NULL` 改为 `WHERE runMode IS NOT NULL`。两文件同一处 `run_mode` 全局替换为 `runMode`，并给 `10_stable.sql:9` 附近现有注释补充一句：

```sql
--   * 新增属性 → ALTER STABLE ADD COLUMN（随物模型版本演进，低频）
--   * 属性列名 = TSL identifier 原样（snake/camel 都不转换），须与写路径 TdengineSqlBuilder 一致
```

- [ ] **Step 4: 编写造数脚本 `sql/tdengine/seed_props.sh`**

```bash
#!/usr/bin/env bash
# EnergyX · 子项目B 造数：TDengine 属性宽表 st_prop_snd_ess_pcs / dev_8000000000000000001
# 前置：ems-tdengine 容器运行（6041 REST 可访问）；已执行过 ALTER STABLE ADD COLUMN runMode。
# 用法：bash sql/tdengine/seed_props.sh
set -euo pipefail

BASE='http://127.0.0.1:6041/rest/sql'
AUTH='root:taosdata'
DB='iot_tsdb_raw'
CHILD='dev_8000000000000000001'
STABLE='st_prop_snd_ess_pcs'

# 1) 确保子表存在（已存在则 no-op；直接 INSERT 子表复用其既有 TAGS）
curl -s -u "$AUTH" \
  -d "CREATE TABLE IF NOT EXISTS $DB.$CHILD USING $DB.$STABLE TAGS ('8000000000000000001','','','snd_ess_pcs')" \
  "$BASE" >/dev/null

# 2) 造数：近 24h 按小时 + 最近 5 分钟逐分钟（数值平滑漂移便于看曲线；runMode=1）
now_ms=$(( $(date +%s) * 1000 ))
rows=''
append() { rows="${rows:+$rows, }$1"; }

for i in $(seq 0 23); do
  ts=$(( now_ms - i * 3600000 ))
  soc=$(awk "BEGIN{printf \"%.1f\", 86.0 - $i * 1.0}")
  voltage=$(awk "BEGIN{printf \"%.1f\", 204.0 + ($i % 5) * 0.5}")
  current=$((18 + i % 3))
  power=$((1031 + i * 10))
  temp=$(awk "BEGIN{printf \"%.1f\", 34.0 + ($i % 4)}")
  append "($ts, 'seed-$ts', 'report', $soc, $voltage, $current, $power, $temp, 1)"
done
for j in 0 1 2 3 4; do
  ts=$(( now_ms - j * 60000 ))
  soc=$(awk "BEGIN{printf \"%.1f\", 86.0 - $j * 0.2}")
  append "($ts, 'seed-$ts', 'report', $soc, 204.0, 18, 1031, 34.0, 1)"
done

sql="INSERT INTO $DB.$CHILD (ts, msg_id, data_type, soc, voltage, current, power, temp, runMode) VALUES $rows"
echo "==> seed $(printf '%s' "$rows" | tr ',' '\n' | wc -l | tr -d ' ') 行"
curl -s -u "$AUTH" -d "$sql" "$BASE"
echo
```

- [ ] **Step 5: 运行造数脚本并验证行数递增**

```bash
bash sql/tdengine/seed_props.sh
curl -s -u root:taosdata -d "SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs WHERE device_id='8000000000000000001'" http://127.0.0.1:6041/rest/sql
```

Expected: 脚本返回 `{"code":0,...}`；count 由 2 增至 ~31。

- [ ] **Step 6: 全链路核对——sim-device 发一条含 runMode 的 report**

终端 1（交互式，见 `docs/sim-device-使用验证指南.md` §2）：

```bash
cd test/sim-device && ./sim-device.sh
# sim-dev> 提示符下：
report soc=86 temp=34 power=1031 current=18 runMode=1 voltage=204
```

Expected: `已上报属性: {soc=86, temp=34, power=1031, current=18, runMode=1, voltage=204}`。随后：

```bash
curl -s -u root:taosdata -d "SELECT msg_id, runMode FROM iot_tsdb_raw.st_prop_snd_ess_pcs WHERE device_id='8000000000000000001' ORDER BY ts DESC LIMIT 1" http://127.0.0.1:6041/rest/sql
```

Expected: 最新一行的 `msg_id` 形如 `snd_ess_pcs_sim-dev-000001_<n>`（真实上报，非 `seed-*`）且 `runMode = 1`（修复后不再被「列不存在」拒收）。若执行环境无法跑交互式 MQTT，记录此步骤由人工执行，其余验证以 seed 脚本为准。

- [ ] **Step 7: Commit**

```bash
git add :/sql/tdengine/10_stable.sql :/sql/tdengine/20_sample_stable.sql :/sql/tdengine/seed_props.sh
git commit -m "fix(tsdb): TDengine stable 属性列 run_mode→runMode 对齐写路径，补造数脚本"
```

---

### Task 2: energy-product TSL by-key 接口 + 单测

设备详情只有 `productKey`（无 productId），新增按 key 取当前生效物模型的读接口。

**Files:**
- Modify: `backend/energy-product/src/main/java/com/energyx/product/service/ProductService.java`
- Modify: `backend/energy-product/src/main/java/com/energyx/product/service/impl/ProductServiceImpl.java`
- Modify: `backend/energy-product/src/main/java/com/energyx/product/web/ProductController.java`
- Create: `backend/energy-product/src/test/java/com/energyx/product/service/ProductServiceImplTest.java`（`src/test` 目录当前不存在，一并创建）

**Interfaces:**
- Consumes: `ProductService.getThingModel(Long productId)`（已存在；未发布返回 null）。
- Produces: `ProductService.getThingModelByProductKey(String productKey): ThingModelView`（产品不存在或未发布 → null）；`GET /api/product/thing-model/by-key?productKey=` → `Result<ThingModelView>`，null → `Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型或不存在：" + productKey)`。Task 5/6 前端经 `productApi.thingModelByKey` 消费。

- [ ] **Step 1: 接口加方法**

`backend/energy-product/src/main/java/com/energyx/product/service/ProductService.java`，在 `getThingModel(Long productId)` 后加：

```java
    /** 按 productKey 查当前生效物模型（设备仅有 productKey）；产品不存在或未发布返回 null */
    ThingModelView getThingModelByProductKey(String productKey);
```

- [ ] **Step 2: 实现**

`backend/energy-product/src/main/java/com/energyx/product/service/impl/ProductServiceImpl.java`，在 `getThingModel` 后加（依赖 MyBatis-Plus 逻辑删除自动追加 `deleted = 0`，等价 spec 的 `AND deleted = 0`）：

```java
    @Override
    public ThingModelView getThingModelByProductKey(String productKey) {
        if (productKey == null || productKey.isBlank()) {
            return null;
        }
        Product product = getBaseMapper().selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (product == null) {
            return null;
        }
        // 不调 getThingModel(productId)：其内部 requireProduct 会再回源一次主键查询（且单测需 mock
        // selectById）。本路径已有 product，直接查当前生效物模型。
        ThingModel model = thingModelMapper.selectOne(new LambdaQueryWrapper<ThingModel>()
                .eq(ThingModel::getProductId, product.getProductId())
                .eq(ThingModel::getIsCurrent, 1));
        return model == null ? null : toView(model);
    }
```

`LambdaQueryWrapper` 已在文件头部 import（`:3`）。

- [ ] **Step 3: Controller 加端点**

`backend/energy-product/src/main/java/com/energyx/product/web/ProductController.java`：import 增 `org.springframework.web.bind.annotation.RequestParam`（`:14` 附近按字母序插入）。在 `getThingModel`（`:78`）后加：

```java
    @GetMapping("/thing-model/by-key")
    public Result<ThingModelView> getThingModelByKey(@RequestParam("productKey") String productKey) {
        ThingModelView view = productService.getThingModelByProductKey(productKey);
        return view == null
                ? Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型或不存在：" + productKey)
                : Result.ok(view);
    }
```

路由无冲突：`/product/thing-model/by-key`（段 3 为字面量 `by-key`）不匹配既有 `/product/{productId}/thing-model`（其段 3 为字面量 `thing-model`）。

- [ ] **Step 4: 写单测（mapper mock，无 Spring 上下文、无 DB）**

创建 `backend/energy-product/src/test/java/com/energyx/product/service/ProductServiceImplTest.java`：

```java
package com.energyx.product.service.impl;

import com.energyx.product.entity.Product;
import com.energyx.product.entity.ThingModel;
import com.energyx.product.mapper.ProductMapper;
import com.energyx.product.mapper.ThingModelMapper;
import com.energyx.product.web.dto.ThingModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ThingModelMapper thingModelMapper;

    private ProductServiceImpl service;

    @BeforeEach
    void setup() {
        service = new ProductServiceImpl(thingModelMapper);
        // ServiceImpl 的 baseMapper 由 MyBatis-Plus 运行期注入，单测里反射塞 mock
        ReflectionTestUtils.setField(service, "baseMapper", productMapper);
    }

    @Test
    void byKey_productNotFound_returnsNull() {
        when(productMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getThingModelByProductKey("no-such-key"));
    }

    @Test
    void byKey_noCurrentModel_returnsNull() {
        Product p = new Product();
        p.setProductId(1L);
        when(productMapper.selectOne(any())).thenReturn(p);
        when(thingModelMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getThingModelByProductKey("snd_ess_pcs"));
    }

    @Test
    void byKey_found_returnsView() {
        Product p = new Product();
        p.setProductId(1L);
        p.setProductKey("snd_ess_pcs");
        ThingModel m = new ThingModel();
        m.setModelId(9L);
        m.setProductId(1L);
        m.setVersion("v1");
        m.setSchemaJson("{}");
        m.setStatus(1);
        m.setIsCurrent(1);
        when(productMapper.selectOne(any())).thenReturn(p);
        when(thingModelMapper.selectOne(any())).thenReturn(m);

        ThingModelView view = service.getThingModelByProductKey("snd_ess_pcs");
        assertEquals("v1", view.getVersion());
        assertEquals(1L, view.getProductId().longValue());
    }

    @Test
    void byKey_blank_returnsNull() {
        assertNull(service.getThingModelByProductKey(""));
        assertNull(service.getThingModelByProductKey(null));
    }
}
```

> 若编译报 `ThingModel`/`Product` 缺少 `setProductId` 等 setter——它们均带 Lombok `@Data`，setter 已生成。`ReflectionTestUtils` 来自 `spring-test`（`spring-boot-starter-test` 内，energy-product pom 已有）。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn -q -pl energy-product -am test
```

Expected: `BUILD SUCCESS`，`ProductServiceImplTest` 4 个用例全绿。（`-am` 连带构建 energy-common；测试为纯 Mockito，不连 MySQL/Nacos。）

- [ ] **Step 6: Commit**

```bash
git add :/backend/energy-product/src/main/java/com/energyx/product/service/ProductService.java :/backend/energy-product/src/main/java/com/energyx/product/service/impl/ProductServiceImpl.java :/backend/energy-product/src/main/java/com/energyx/product/web/ProductController.java :/backend/energy-product/src/test/java/com/energyx/product/service/ProductServiceImplTest.java
git commit -m "feat(product): TSL by-key 读接口（设备仅有 productKey），含单测"
```

---

### Task 3: energy-tsdb 历史查询（构建器 + service + controller + 网关路由）

新增 TDengine 属性历史读接口。纯 SQL 构建器可单测；查询 service 直连 TAOS-RS（照 `TdengineWriter` 单连接模式 + PreparedStatement 占位符）；controller 参数校验走 `BusinessException(PARAM_INVALID)`（400）。

**Files:**
- Create: `backend/energy-tsdb/src/main/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilder.java`
- Create: `backend/energy-tsdb/src/test/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilderTest.java`
- Create: `backend/energy-tsdb/src/main/java/com/energyx/tsdb/service/TdengineQueryService.java`
- Create: `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/TsdbController.java`
- Create: `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryView.java`
- Create: `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryRecord.java`
- Modify: `backend/energy-gateway/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `TsdbProperties`（`energyx.tsdb.*`，含 `rawDb`/`jdbcUrl`/`jdbcUsername`/`jdbcPassword`，密码经 Nacos `energy-shared.yaml` 注入）；`TdengineSqlBuilder.isSafeKey/isSafeColumn`（静态方法，`com.energyx.tsdb.sql`）。
- Produces: `GET /api/tsdb/property/history?deviceId&productKey&identifiers(逗号分隔,≤10)&startTime/endTime(ms,可选,缺省近24h)&order(asc|desc,默认desc)&page&size` → `Result<PropertyHistoryView>`。`ts`/`total` 为 primitive long（数字序列化）。Task 5/6 前端经 `tsdbApi.propertyHistory` 消费。

- [ ] **Step 1: 写失败测试 `TdengineQuerySqlBuilderTest`**

创建 `backend/energy-tsdb/src/test/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilderTest.java`（照 `TdengineSqlBuilderTest` 模式：纯 SQL 字符串断言）：

```java
package com.energyx.tsdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TdengineQuerySqlBuilderTest {

    @Test
    void dataSql_asc_containsBacktickColumnsAndPlaceholders() {
        String sql = TdengineQuerySqlBuilder.buildDataSql(
                "iot_tsdb_raw", "snd_ess_pcs", List.of("soc", "voltage"),
                "8000000000000000001", 1700000000000L, 1700003600000L, true, 1000, 0);
        assertTrue(sql.contains("SELECT ts, `soc`, `voltage`"));
        assertTrue(sql.contains("FROM iot_tsdb_raw.st_prop_snd_ess_pcs"));
        assertTrue(sql.contains("WHERE device_id = ? AND ts >= ? AND ts <= ?"));
        assertTrue(sql.contains("ORDER BY ts ASC"));
        assertTrue(sql.contains("LIMIT ? OFFSET ?"));
    }

    @Test
    void dataSql_desc() {
        String sql = TdengineQuerySqlBuilder.buildDataSql(
                "iot_tsdb_raw", "snd_ess_pcs", List.of("temp"), "8000000000000000001", 1L, 2L, false, 20, 40);
        assertTrue(sql.contains("ORDER BY ts DESC"));
    }

    @Test
    void invalidProductKey_throws() {
        assertThrows(IllegalArgumentException.class, () -> TdengineQuerySqlBuilder.buildDataSql(
                "db", "bad key", List.of("soc"), "d1", 1L, 2L, true, 10, 0));
    }

    @Test
    void invalidIdentifier_throws() {
        assertThrows(IllegalArgumentException.class, () -> TdengineQuerySqlBuilder.buildDataSql(
                "db", "pk", List.of("soc; drop table x"), "d1", 1L, 2L, true, 10, 0));
    }

    @Test
    void countSql() {
        String sql = TdengineQuerySqlBuilder.buildCountSql(
                "iot_tsdb_raw", "snd_ess_pcs", "8000000000000000001", 1L, 2L);
        assertTrue(sql.contains("SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs"));
        assertTrue(sql.contains("WHERE device_id = ? AND ts >= ? AND ts <= ?"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn -q -pl energy-tsdb -am test
```

Expected: `TdengineQuerySqlBuilderTest` 编译失败（类不存在）。

- [ ] **Step 3: 实现纯构建器 `TdengineQuerySqlBuilder`**

创建 `backend/energy-tsdb/src/main/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilder.java`：

```java
package com.energyx.tsdb.sql;

import java.util.List;

/**
 * TDengine 历史查询 SQL 纯构造器（无副作用，便于单测）。
 *
 * <p>与 {@link TdengineSqlBuilder} 共享 isSafeKey/isSafeColumn 约定；请求的 identifiers
 * 已由 service 层 DESCRIBE 白名单过滤，此处仅做防御性安全校验（禁止拼接未校验列名）。
 * 列名反引号包裹；device_id/ts 均为 {@code ?} 占位符，由 service 层 PreparedStatement 绑定。</p>
 */
public final class TdengineQuerySqlBuilder {

    private TdengineQuerySqlBuilder() {
    }

    /** 属性历史 data 查询：SELECT ts, `id1`, `id2` ... FROM {db}.st_prop_{productKey} WHERE ... ORDER BY ts ... LIMIT ? OFFSET ? */
    public static String buildDataSql(String db, String productKey, List<String> identifiers,
                                      String deviceId, long startTime, long endTime,
                                      boolean asc, int limit, int offset) {
        if (!TdengineSqlBuilder.isSafeKey(productKey)) {
            throw new IllegalArgumentException("productKey 非法: " + productKey);
        }
        StringBuilder cols = new StringBuilder("ts");
        for (String id : identifiers) {
            if (!TdengineSqlBuilder.isSafeColumn(id)) {
                throw new IllegalArgumentException("非法属性标识: " + id);
            }
            cols.append(", `").append(id).append('`');
        }
        return "SELECT " + cols
                + " FROM " + db + ".st_prop_" + productKey
                + " WHERE device_id = ? AND ts >= ? AND ts <= ?"
                + " ORDER BY ts " + (asc ? "ASC" : "DESC")
                + " LIMIT ? OFFSET ?";
    }

    /** 属性历史 count 查询（同过滤条件，供分页 total）。 */
    public static String buildCountSql(String db, String productKey, String deviceId,
                                       long startTime, long endTime) {
        if (!TdengineSqlBuilder.isSafeKey(productKey)) {
            throw new IllegalArgumentException("productKey 非法: " + productKey);
        }
        return "SELECT count(*) FROM " + db + ".st_prop_" + productKey
                + " WHERE device_id = ? AND ts >= ? AND ts <= ?";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn -q -pl energy-tsdb -am test
```

Expected: `BUILD SUCCESS`，`TdengineQuerySqlBuilderTest` 5 个用例全绿。

- [ ] **Step 5: DTO（primitive long）**

创建 `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryRecord.java`：

```java
package com.energyx.tsdb.web.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 属性历史单行。ts 用 primitive long（JacksonConfig 只装箱 Long→字符串，primitive 保持数字）。
 */
@Data
public class PropertyHistoryRecord {

    /** epoch 毫秒 */
    private long ts;

    /** 所选属性快照；某属性该行为 NULL（设备未上报）时省略该键 */
    private Map<String, Object> values = new LinkedHashMap<>();
}
```

创建 `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryView.java`：

```java
package com.energyx.tsdb.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 属性历史分页视图。total 用 primitive long 保持数字序列化。
 */
@Data
public class PropertyHistoryView {

    private String deviceId;
    private String productKey;
    /** 命中行总数（分页 total） */
    private long total;
    private List<PropertyHistoryRecord> records;
}
```

- [ ] **Step 6: 查询 service `TdengineQueryService`**

创建 `backend/energy-tsdb/src/main/java/com/energyx/tsdb/service/TdengineQueryService.java`。DESCRIBE 白名单按列**序号**读取（1=field、4=note，避免 JDBC 列名大小写歧义），过滤公共列与 `note="TAG"` 行；白名单缓存 60s。

```java
package com.energyx.tsdb.service;

import com.energyx.tsdb.config.TsdbProperties;
import com.energyx.tsdb.sql.TdengineQuerySqlBuilder;
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

/**
 * TDengine 属性历史查询。
 *
 * <p>连接照 {@code TdengineWriter} 的进程级单连接模式（懒初始化 + 失效重建重试一次）；
 * 查询用 PreparedStatement 绑定 {@code ?}（device_id 字符串、ts 毫秒 long、offset/limit int）。
 * 属性列白名单 = {@code DESCRIBE} 结果剔除公共列(ts/msg_id/data_type)与 TAG 行，缓存 60s。</p>
 */
@Slf4j
@Service
public class TdengineQueryService {

    private static final Set<String> COMMON_COLUMNS = Set.of("ts", "msg_id", "data_type");
    private static final long WHITELIST_TTL_MS = 60_000L;

    private final TsdbProperties props;
    private volatile Connection connection;
    private volatile Map<String, Set<String>> columnCache = Map.of();
    private volatile long cacheLoadedAt = 0L;

    public TdengineQueryService(TsdbProperties props) {
        this.props = props;
    }

    /** 查询属性历史：data + count 各一条 PreparedStatement；白名单过滤后的 identifiers 为空 → 抛参数异常。 */
    public PropertyHistoryView queryHistory(String deviceId, String productKey,
                                            List<String> identifiers,
                                            long startTime, long endTime,
                                            boolean asc, int page, int size) throws SQLException {
        Set<String> whitelist = columnWhitelist(productKey);
        List<String> selected = identifiers.stream()
                .filter(whitelist::contains)
                .distinct()
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("请求的属性均不在该产品物模型中");
        }

        int offset = (page - 1) * size;
        String dataSql = TdengineQuerySqlBuilder.buildDataSql(
                props.getRawDb(), productKey, selected, deviceId, startTime, endTime, asc, size, offset);
        String countSql = TdengineQuerySqlBuilder.buildCountSql(
                props.getRawDb(), productKey, deviceId, startTime, endTime);

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
            } catch (SQLException e) {
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
        if (cached != null && now - cacheLoadedAt < WHITELIST_TTL_MS) {
            return cached;
        }
        Set<String> whitelist = new LinkedHashSet<>();
        String sql = "DESCRIBE " + props.getRawDb() + ".st_prop_" + productKey;
        int attempt = 0;
        while (true) {
            try {
                Connection conn = getConnection();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(sql)) {
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
            } catch (SQLException e) {
                attempt++;
                closeQuietly();
                if (attempt >= 2) {
                    throw e;
                }
                log.warn("[Tsdb] DESCRIBE 异常，重建连接重试 errorCode={}", e.getErrorCode(), e);
            }
        }
        columnCache = Map.of(productKey, whitelist);
        cacheLoadedAt = now;
        return whitelist;
    }

    private Connection getConnection() throws SQLException {
        Connection c = connection;
        if (c == null || c.isClosed()) {
            synchronized (this) {
                if (connection == null || connection.isClosed()) {
                    Connection nc = DriverManager.getConnection(
                            props.getJdbcUrl(), props.getJdbcUsername(), props.getJdbcPassword());
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
            } catch (SQLException ignore) {
                // 关闭异常可忽略
            }
        }
    }
}
```

- [ ] **Step 7: controller `TsdbController`**

创建 `backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/TsdbController.java`（参数校验抛 `BusinessException(PARAM_INVALID)` → GlobalExceptionHandler 映射 400；TDengine 不可用等其它异常交给 `Throwable` handler → 500）：

```java
package com.energyx.tsdb.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import com.energyx.tsdb.service.TdengineQueryService;
import com.energyx.tsdb.sql.TdengineSqlBuilder;
import com.energyx.tsdb.web.dto.PropertyHistoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * TDengine 时序读接口。网关 /api/tsdb/** StripPrefix=1 → controller 映射 /tsdb（不带 /api）。
 */
@RestController
@RequestMapping("/tsdb")
public class TsdbController {

    private static final int MAX_IDENTIFIERS = 10;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24L * HOUR_MS;

    private final TdengineQueryService queryService;

    public TsdbController(TdengineQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/property/history")
    public Result<PropertyHistoryView> propertyHistory(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("productKey") String productKey,
            @RequestParam("identifiers") String identifiers,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "order", defaultValue = "desc") String order,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) throws Exception {

        if (!TdengineSqlBuilder.isSafeKey(productKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "productKey 非法");
        }
        List<String> ids = Arrays.stream(identifiers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty() || ids.size() > MAX_IDENTIFIERS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "identifiers 须为 1~10 个");
        }
        if (page == null || page < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "page 须 ≥1");
        }
        if (size == null || size < 1 || size > 1000) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "size 须为 1~1000");
        }
        boolean asc;
        if ("asc".equalsIgnoreCase(order)) {
            asc = true;
        } else if ("desc".equalsIgnoreCase(order)) {
            asc = false;
        } else {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "order 仅支持 asc|desc");
        }

        long now = System.currentTimeMillis();
        long start = startTime != null ? startTime : now - DAY_MS;
        long end = endTime != null ? endTime : now;
        if (start >= end) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时间范围非法：startTime 须早于 endTime");
        }

        try {
            PropertyHistoryView view = queryService.queryHistory(deviceId, productKey, ids, start, end, asc, page, size);
            return Result.ok(view);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
```

- [ ] **Step 8: 写 controller 参数校验单测**

创建 `backend/energy-tsdb/src/test/java/com/energyx/tsdb/web/TsdbControllerTest.java`：

```java
package com.energyx.tsdb.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.tsdb.service.TdengineQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TsdbControllerTest {

    @Mock
    private TdengineQueryService queryService;

    private TsdbController controller;

    @BeforeEach
    void setup() {
        controller = new TsdbController(queryService);
    }

    @Test
    void emptyIdentifiers_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", " , ,", null, null, "desc", 1, 20));
    }

    @Test
    void tooManyIdentifiers_throwsParamInvalid() {
        String ids = "a,b,c,d,e,f,g,h,i,j,k";
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", ids, null, null, "desc", 1, 20));
    }

    @Test
    void sizeOutOfRange_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "desc", 1, 0));
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "desc", 1, 1001));
    }

    @Test
    void invalidOrder_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "sideways", 1, 20));
    }

    @Test
    void invalidTimeRange_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", 2000L, 1000L, "desc", 1, 20));
    }

    @Test
    void invalidProductKey_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "bad key", "soc", null, null, "desc", 1, 20));
    }
}
```

- [ ] **Step 9: 跑全部测试**

```bash
cd backend && mvn -q -pl energy-tsdb -am test
```

Expected: `BUILD SUCCESS`，`TdengineQuerySqlBuilderTest` 5 + `TsdbControllerTest` 6 全绿。（energy-tsdb 无需改 pom：`spring-boot-starter-web`/`validation` 由 energy-common 传递在 classpath；`server.port: 8112` 已配，`@RestController` 直接注册到既有 Tomcat。）

- [ ] **Step 10: 网关加路由**

`backend/energy-gateway/src/main/resources/application.yml`：在 `energy-ems` 路由块（`:64-69`）后、`# ---- WebSocket 转发` 注释（`:70`）前插入：

```yaml
        - id: energy-tsdb
          uri: lb://energy-tsdb
          predicates:
            - Path=/api/tsdb/**
          filters:
            - StripPrefix=1
```

- [ ] **Step 11: 手工冒烟读接口（重启 energy-tsdb 后）**

```bash
curl -s -u admin:admin123 -X POST http://127.0.0.1:8000/api/system/auth/login  # 略，取 token
curl -s "http://127.0.0.1:8000/api/tsdb/property/history?deviceId=8000000000000000001&productKey=snd_ess_pcs&identifiers=soc,voltage,runMode&order=asc&page=1&size=5" \
  -H "Authorization: Bearer $TOKEN"
```

Expected: `Result<PropertyHistoryView>`，`code:0`，`total` ≥ 29，`records[].values` 含 soc/voltage/runMode；`ts` 为数字（非字符串）。

- [ ] **Step 12: Commit**

```bash
git add :/backend/energy-tsdb/src/main/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilder.java :/backend/energy-tsdb/src/test/java/com/energyx/tsdb/sql/TdengineQuerySqlBuilderTest.java :/backend/energy-tsdb/src/main/java/com/energyx/tsdb/service/TdengineQueryService.java :/backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/TsdbController.java :/backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryView.java :/backend/energy-tsdb/src/main/java/com/energyx/tsdb/web/dto/PropertyHistoryRecord.java :/backend/energy-tsdb/src/test/java/com/energyx/tsdb/web/TsdbControllerTest.java :/backend/energy-gateway/src/main/resources/application.yml
git commit -m "feat(tsdb): 属性历史读接口（纯 SQL 构建器 + TAOS-RS 查询 service + controller），网关 /api/tsdb/** 路由"
```

---

### Task 4: energy-shadow `ShadowView.lastReportedTime`（additive）

`GET /api/shadow/{deviceId}` 增加 `lastReportedTime`（ISO 本地时间字符串），运行状态 tab 顶部「最后上报」用。

**Files:**
- Modify: `backend/energy-shadow/src/main/java/com/energyx/shadow/web/dto/ShadowView.java`
- Modify: `backend/energy-shadow/src/main/java/com/energyx/shadow/service/ShadowService.java`

**Interfaces:**
- Consumes: `ShadowMapper.selectByDeviceId`（已 select `last_reported_time` → `ShadowRow.getLastReportedTime(): LocalDateTime`）。
- Produces: `ShadowView.lastReportedTime: String`（ISO `yyyy-MM-dd'T'HH:mm:ss`；行不存在 → null）。Task 5/6 前端 `ShadowView.lastReportedTime?: string` 消费。

- [ ] **Step 1: DTO 增字段**

`backend/energy-shadow/src/main/java/com/energyx/shadow/web/dto/ShadowView.java`，在 `version` 后加：

```java
    /** 最后上报时间（ISO 本地时间字符串，如 2026-08-09T11:56:00）；行不存在时为 null */
    private String lastReportedTime;
```

- [ ] **Step 2: getShadow 携带时间**

`backend/energy-shadow/src/main/java/com/energyx/shadow/service/ShadowService.java`：import 增 `java.time.format.DateTimeFormatter`（`LocalDateTime` 已 import）。替换 `getShadow` 方法体（`:109-131`）为：

```java
    /** 影子合并视图：Redis 热路径优先，未命中回 MySQL；last_reported_time 仅存于 MySQL 行 */
    public ShadowView getShadow(long deviceId) {
        ShadowView view = new ShadowView();
        view.setDeviceId(deviceId);
        Map<String, Object> reported = readReportedRedis(deviceId);
        Map<String, Object> desired = readDesiredRedis(deviceId);
        Integer version = null;
        LocalDateTime lastReportedTime = null;
        if (reported.isEmpty() || desired.isEmpty()) {
            ShadowRow row = shadowMapper.selectByDeviceId(deviceId);
            if (row != null) {
                if (reported.isEmpty()) {
                    reported = parse(row.getReported());
                }
                if (desired.isEmpty()) {
                    desired = parse(row.getDesired());
                }
                version = row.getVersion();
                lastReportedTime = row.getLastReportedTime();
            }
        } else {
            // Redis 热路径：reported/desired 齐备但缓存不含时间字段 → 补一次主键查询（PK 命中，管理端可接受）
            ShadowRow row = shadowMapper.selectByDeviceId(deviceId);
            if (row != null) {
                lastReportedTime = row.getLastReportedTime();
            }
        }
        view.setReported(reported);
        view.setDesired(desired);
        view.setVersion(version);
        view.setLastReportedTime(lastReportedTime == null ? null
                : lastReportedTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return view;
    }
```

> 只新增字段 + 热路径补查一次主键，`version` 语义不变（热路径仍为 null，仅回源路径赋值）。

- [ ] **Step 3: 编译**

```bash
cd backend && mvn -q -pl energy-shadow -am compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add :/backend/energy-shadow/src/main/java/com/energyx/shadow/web/dto/ShadowView.java :/backend/energy-shadow/src/main/java/com/energyx/shadow/service/ShadowService.java
git commit -m "feat(shadow): ShadowView 增加 lastReportedTime（additive），Redis 热路径补查时间"
```

---

### Task 5: 前端类型 / API / 工具 + vitest

`models.ts` 新增时序与物模型类型、`ShadowView` 增 `lastReportedTime`；`product.ts` 加 `thingModelByKey`；新建 `tsdb.ts` 与 `thingModel.ts` 工具 + 单测。

**Files:**
- Modify: `frontend/src/types/models.ts`
- Modify: `frontend/src/api/product.ts`
- Create: `frontend/src/api/tsdb.ts`
- Create: `frontend/src/utils/thingModel.ts`
- Test: `frontend/src/utils/__tests__/thingModel.spec.ts`
- Test: `frontend/src/api/__tests__/tsdb.spec.ts`

**Interfaces:**
- Consumes: `http`（`frontend/src/api/http.ts`，默认导出 axios 实例，`http.get(url, { params })`）；`ThingModelView`（已存在）。
- Produces: 类型 `TsProperty`/`ThingModelSchema`/`PropertyHistoryRecord`/`PropertyHistoryView`；`productApi.thingModelByKey(productKey)`；`tsdbApi.propertyHistory(params)`（identifiers join `,`）；`parseThingModel(schemaJson)`。Task 6 全部消费。

- [ ] **Step 1: `models.ts` 增类型**

`frontend/src/types/models.ts`：`ShadowView`（`:26-34`）增 `lastReportedTime?: string`；`ThingModelView`（`:308-315`）后新增：

```ts
// ---------------- 时序历史 Tsdb ----------------

/** 物模型属性（TSL properties 条目，parseThingModel 解析产物） */
export interface TsProperty {
  identifier: string
  name: string
  dataType: string
  unit?: string
  accessMode?: string
  /** 枚举属性取值说明（runMode 等）；本子项目仅透传不映射 */
  enumValues?: Array<{ value: number; desc: string }>
}

/** 物模型 schema_json 顶层结构（本子项目只用 properties） */
export interface ThingModelSchema {
  properties: TsProperty[]
  services: unknown[]
  events: unknown[]
}

/** TDengine 属性历史单行（某属性该行为 NULL 时 values 省略该键） */
export interface PropertyHistoryRecord {
  /** epoch 毫秒 */
  ts: number
  values: Record<string, number | string | null>
}

/** TDengine 属性历史分页视图（ts/total 均为数字） */
export interface PropertyHistoryView {
  deviceId: string
  productKey: string
  total: number
  records: PropertyHistoryRecord[]
}
```

- [ ] **Step 2: `product.ts` 加 thingModelByKey**

`frontend/src/api/product.ts`，在 `thingModelGet` 后加：

```ts
  /** 按 productKey 取当前生效物模型（设备仅有 productKey，无 productId）；未发布后端返回业务错误 */
  thingModelByKey(productKey: string): Promise<ThingModelView> {
    return http.get('/api/product/thing-model/by-key', { params: { productKey } })
  },
```

- [ ] **Step 3: 新建 `tsdb.ts`**

`frontend/src/api/tsdb.ts`：

```ts
import http from './http'
import type { PropertyHistoryView } from '@/types/models'

export interface TsHistoryParams {
  deviceId: string
  productKey: string
  /** 物模型属性标识，1~10 个；序列化为逗号分隔 */
  identifiers: string[]
  /** epoch 毫秒；缺省近 24h */
  startTime?: number
  endTime?: number
  /** 图表用 asc、表格用 desc，默认 desc */
  order?: 'asc' | 'desc'
  page?: number
  size?: number
}

/** 时序 API（网关 /api/tsdb/** StripPrefix=1 → energy-tsdb） */
export const tsdbApi = {
  /** 属性历史查询（TDengine 宽表） */
  propertyHistory(params: TsHistoryParams): Promise<PropertyHistoryView> {
    const query: Record<string, unknown> = {
      deviceId: params.deviceId,
      productKey: params.productKey,
      identifiers: params.identifiers.join(','),
      order: params.order ?? 'desc',
      page: params.page ?? 1,
      size: params.size ?? 20,
    }
    if (params.startTime !== undefined) query.startTime = params.startTime
    if (params.endTime !== undefined) query.endTime = params.endTime
    return http.get('/api/tsdb/property/history', { params: query })
  },
}
```

- [ ] **Step 4: 新建 `thingModel.ts` 工具**

`frontend/src/utils/thingModel.ts`：

```ts
import type { ThingModelSchema } from '@/types/models'

/** 解析物模型 schema_json；任何失败返回空结构（页面显示空态而非崩溃） */
export function parseThingModel(schemaJson: string): ThingModelSchema {
  try {
    const raw = JSON.parse(schemaJson) as Partial<ThingModelSchema>
    return {
      properties: Array.isArray(raw.properties) ? raw.properties : [],
      services: Array.isArray(raw.services) ? raw.services : [],
      events: Array.isArray(raw.events) ? raw.events : [],
    }
  } catch {
    return { properties: [], services: [], events: [] }
  }
}
```

- [ ] **Step 5: 写 vitest**

`frontend/src/utils/__tests__/thingModel.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { parseThingModel } from '@/utils/thingModel'

describe('parseThingModel', () => {
  it('解析合法 schema 的属性/服务/事件', () => {
    const schema = parseThingModel(JSON.stringify({
      properties: [{ identifier: 'soc', name: '荷电状态', dataType: 'float', unit: '%', accessMode: 'r' }],
      services: [{ identifier: 'setPower' }],
      events: [],
    }))
    expect(schema.properties).toHaveLength(1)
    expect(schema.properties[0].identifier).toBe('soc')
    expect(schema.services).toHaveLength(1)
    expect(schema.events).toEqual([])
  })

  it('缺省数组字段 → 空数组', () => {
    const schema = parseThingModel('{"properties":[]}')
    expect(schema.properties).toEqual([])
    expect(schema.services).toEqual([])
    expect(schema.events).toEqual([])
  })

  it('畸形 JSON → 空结构', () => {
    expect(parseThingModel('{bad json')).toEqual({ properties: [], services: [], events: [] })
  })
})
```

`frontend/src/api/__tests__/tsdb.spec.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from '@/api/http'
import { tsdbApi } from '@/api/tsdb'

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const mockedGet = vi.mocked(http.get)

describe('tsdbApi.propertyHistory', () => {
  beforeEach(() => { mockedGet.mockReset() })

  it('identifiers 数组 join 为逗号分隔', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: '8000000000000000001', productKey: 'snd_ess_pcs', identifiers: ['soc', 'voltage'] })
    expect(mockedGet).toHaveBeenCalledWith('/api/tsdb/property/history', {
      params: expect.objectContaining({ identifiers: 'soc,voltage' }),
    })
  })

  it('缺省 order=desc page=1 size=20；可选时间缺省不带', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: 'd', productKey: 'pk', identifiers: ['temp'] })
    expect(mockedGet).toHaveBeenCalledWith('/api/tsdb/property/history', {
      params: expect.objectContaining({ order: 'desc', page: 1, size: 20 }),
    })
    const params = mockedGet.mock.calls[0][1].params as Record<string, unknown>
    expect(params.startTime).toBeUndefined()
  })

  it('显式 startTime/endTime 透传为数字', async () => {
    mockedGet.mockResolvedValue({ deviceId: 'd', productKey: 'pk', total: 0, records: [] })
    await tsdbApi.propertyHistory({ deviceId: 'd', productKey: 'pk', identifiers: ['soc'], startTime: 1000, endTime: 2000 })
    const params = mockedGet.mock.calls[0][1].params as Record<string, unknown>
    expect(params.startTime).toBe(1000)
    expect(params.endTime).toBe(2000)
  })
})
```

- [ ] **Step 6: 跑前端测试 + 类型检查**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npm test
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vue-tsc --noEmit
```

Expected: vitest 全绿（新增 6 用例）；`vue-tsc` EXIT 0。

- [ ] **Step 7: Commit**

```bash
git add :/frontend/src/types/models.ts :/frontend/src/api/product.ts :/frontend/src/api/tsdb.ts :/frontend/src/utils/thingModel.ts :/frontend/src/utils/__tests__/thingModel.spec.ts :/frontend/src/api/__tests__/tsdb.spec.ts
git commit -m "feat(web): 时序历史 API/类型与物模型解析工具 + vitest"
```

---

### Task 6: Device.vue 抽屉改造（tabs + 运行状态 UI）

抽屉 480→820px，内容改 `el-tabs`（基本信息 / 运行状态 lazy）；运行状态 tab：最新值卡片区 + 历史查询区（时间范围 + 属性单选 + 折线图 + 分页表格 + 空态/错误处理）。

**Files:**
- Modify: `frontend/src/views/Device.vue`

**Interfaces:**
- Consumes: `shadowApi.getShadow`、`productApi.thingModelByKey`、`tsdbApi.propertyHistory`、`parseThingModel`、`useEChart`、`toLocal`/`tsToLocal`、`TsProperty`/`ThingModelSchema`/`PropertyHistoryView`/`ShadowView`。
- Produces: 运行状态 tab 完整 UI（无外部依赖，Task 7 冒烟消费）。

- [ ] **Step 1: script 增 import 与运行状态 state**

`frontend/src/views/Device.vue` `<script setup>`：增 import

```ts
import { useEChart } from '@/composables/useEChart'
import { shadowApi } from '@/api/shadow'
import { tsdbApi } from '@/api/tsdb'
import { parseThingModel } from '@/utils/thingModel'
import { tsToLocal } from '@/utils/alarmFormat'
import type { PropertyHistoryView, ShadowView, ThingModelSchema } from '@/types/models'
```

在 `openDetail`（`:126`）前增运行状态 state 与函数：

```ts
// ---- 详情抽屉：基本信息 / 运行状态 ----
const activeTab = ref('basic')
const runtimeLoading = ref(false)
const shadow = ref<ShadowView | null>(null)
const model = ref<ThingModelSchema | null>(null)
const lastReported = ref('')
const timeRange = ref<[string, string] | null>(null)
const selProp = ref('')
const historyLoading = ref(false)
const hasChartData = ref(false)
const chartEl = ref<HTMLElement>()
const { render } = useEChart(chartEl)
const historyTable = ref<PropertyHistoryView>({ deviceId: '', productKey: '', total: 0, records: [] })
const historyPage = ref(1)
const historySize = ref(20)

function resetRuntime() {
  shadow.value = null
  model.value = null
  lastReported.value = ''
  historyTable.value = { deviceId: '', productKey: '', total: 0, records: [] }
  historyPage.value = 1
  timeRange.value = null
  selProp.value = ''
  hasChartData.value = false
  render({ xAxis: { type: 'time' }, yAxis: { type: 'value' }, series: [] })
}

function defaultTimeRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 24 * 3600 * 1000)
  const p = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) => `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  return [fmt(start), fmt(end)]
}

function propName(id: string): string {
  return model.value?.properties.find((x) => x.identifier === id)?.name ?? id
}

function rangeToEpoch(r: [string, string]): [number, number] {
  return [new Date(r[0]).getTime(), new Date(r[1]).getTime()]
}

/** activeTab 切到 runtime 且当前设备未加载时并行拉 shadow + TSL */
async function loadRuntime() {
  if (!detail.value || activeTab.value !== 'runtime') return
  if (shadow.value) return
  runtimeLoading.value = true
  try {
    const [sh, tm] = await Promise.all([
      shadowApi.getShadow(String(detail.value.deviceId)),
      productApi.thingModelByKey(detail.value.productKey).catch(() => null),
    ])
    shadow.value = sh
    lastReported.value = sh.lastReportedTime ?? ''
    model.value = tm ? parseThingModel(tm.schemaJson) : { properties: [], services: [], events: [] }
    if (model.value.properties.length) selProp.value = model.value.properties[0].identifier
    if (!timeRange.value) timeRange.value = defaultTimeRange()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    runtimeLoading.value = false
  }
}

async function queryHistory() {
  if (!detail.value || !selProp.value || !timeRange.value) return
  historyLoading.value = true
  const [start, end] = rangeToEpoch(timeRange.value)
  try {
    const chartData = await tsdbApi.propertyHistory({
      deviceId: String(detail.value.deviceId), productKey: detail.value.productKey,
      identifiers: [selProp.value], startTime: start, endTime: end,
      order: 'asc', page: 1, size: 1000,
    })
    renderChart(chartData.records, selProp.value)
    historyPage.value = 1
    historyTable.value = await tsdbApi.propertyHistory({
      deviceId: String(detail.value.deviceId), productKey: detail.value.productKey,
      identifiers: [selProp.value], startTime: start, endTime: end,
      order: 'desc', page: historyPage.value, size: historySize.value,
    })
  } catch {
    ElMessage.error('历史数据查询失败')
    hasChartData.value = false
    render({ xAxis: { type: 'time' }, yAxis: { type: 'value' }, series: [] })
    if (detail.value) {
      historyTable.value = { deviceId: String(detail.value.deviceId), productKey: detail.value.productKey, total: 0, records: [] }
    }
  } finally {
    historyLoading.value = false
  }
}

async function onTablePage(p: number) {
  if (!detail.value || !selProp.value || !timeRange.value) return
  historyLoading.value = true
  const [start, end] = rangeToEpoch(timeRange.value)
  try {
    historyTable.value = await tsdbApi.propertyHistory({
      deviceId: String(detail.value.deviceId), productKey: detail.value.productKey,
      identifiers: [selProp.value], startTime: start, endTime: end,
      order: 'desc', page: p, size: historySize.value,
    })
  } catch {
    ElMessage.error('历史数据查询失败')
  } finally {
    historyLoading.value = false
  }
}

function renderChart(records: PropertyHistoryRecord[], identifier: string) {
  const prop = model.value?.properties.find((x) => x.identifier === identifier)
  const unit = prop?.unit ? ` (${prop.unit})` : ''
  hasChartData.value = records.length > 0
  render({
    tooltip: { trigger: 'axis' },
    grid: { left: 52, right: 24, top: 24, bottom: 44 },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', name: unit },
    series: [{
      type: 'line',
      showSymbol: false,
      connectNulls: true,
      data: records
        .filter((r) => r.values[identifier] != null)
        .map((r) => [r.ts, r.values[identifier]] as [number, unknown]),
    }],
  })
}
```

`PropertyHistoryRecord` 需加入 `import type { ... }`（与上同列）。同时改 `openDetail` 开头重置运行状态：

```ts
async function openDetail(row: Device) {
  resetRuntime()
  activeTab.value = 'basic'
  detail.value = row
  drawerVisible.value = true
  cred.value = null
  plainSecret.value = ''
  try {
    const [d, c] = await Promise.all([deviceApi.detail(row.deviceId), deviceApi.credential(row.deviceId)])
    detail.value = d
    cred.value = c
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
```

并在 `onMounted`（`:175`）前加 watch（`flush: 'post'` 保证 lazy tab 内容挂载后 chartEl 已就位；useEChart 的 pending 缓存兜底时序）：

```ts
watch([detail, activeTab], () => { void loadRuntime() }, { flush: 'post' })
```

- [ ] **Step 2: template 抽屉改 el-tabs**

`<el-drawer ... size="480px" ...>` 改 `size="820px"`。抽屉 `<template v-if="detail">` 内容整体搬进 `el-tabs`：现有 `el-descriptions` + 凭据卡片原样进 `name="basic"` tab，其后加 `name="runtime"` tab：

```html
<el-tabs v-model="activeTab">
  <el-tab-pane name="basic" label="基本信息">
    <el-descriptions :column="2" border size="small" class="desc">
      <!-- 现有全部 el-descriptions-item 原样 -->
    </el-descriptions>
    <div class="ex-card cred-card">
      <!-- 现有凭据卡片原样 -->
    </div>
  </el-tab-pane>
  <el-tab-pane name="runtime" label="运行状态" lazy>
    <div v-loading="runtimeLoading" class="runtime-pane">
      <template v-if="model">
        <div class="runtime-head">
          <span class="rt-label">最后上报：</span>
          <span class="ex-num">{{ toLocal(lastReported) }}</span>
        </div>
        <div class="rt-cards">
          <div v-for="p in model.properties" :key="p.identifier" class="rt-card">
            <div class="rt-card-name">{{ p.name }}</div>
            <div class="rt-card-value">
              {{ shadow?.reported?.[p.identifier] ?? '—' }}
              <span v-if="p.unit && shadow?.reported?.[p.identifier] != null" class="rt-card-unit">{{ p.unit }}</span>
            </div>
            <div class="rt-card-id">{{ p.identifier }}</div>
          </div>
        </div>
        <div class="hist-card">
          <div class="hist-controls">
            <el-date-picker v-model="timeRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
              start-placeholder="开始时间" end-placeholder="结束时间"
              :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]" />
            <el-select v-model="selProp" style="width: 190px">
              <el-option v-for="p in model.properties" :key="p.identifier" :label="`${p.name} (${p.identifier})`" :value="p.identifier" />
            </el-select>
            <el-button type="primary" @click="queryHistory">查询</el-button>
          </div>
          <div ref="chartEl" class="hist-chart" v-loading="historyLoading"></div>
          <div v-if="!hasChartData && !historyLoading" class="hist-empty">所选属性在该时间范围无数据</div>
          <el-table :data="historyTable.records" size="small" empty-text="暂无数据" v-loading="historyLoading">
            <el-table-column label="时间" min-width="160">
              <template #default="{ row }"><span class="ex-num">{{ tsToLocal(row.ts) }}</span></template>
            </el-table-column>
            <el-table-column :label="propName(selProp)" min-width="120">
              <template #default="{ row }">{{ row.values[selProp] ?? '—' }}</template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination v-model:current-page="historyPage" v-model:page-size="historySize" :total="historyTable.total"
              :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
              @size-change="historyPage = 1; void queryHistory()" @current-change="onTablePage" />
          </div>
        </div>
      </template>
      <el-empty v-else-if="!runtimeLoading" description="产品未发布物模型" />
    </div>
  </el-tab-pane>
</el-tabs>
```

- [ ] **Step 3: style 增运行状态样式**

`<style scoped>` 追加：

```css
.runtime-pane { min-height: 320px; }
.runtime-head { margin-bottom: 10px; font-size: 13px; color: var(--ex-ink-2); }
.rt-label { color: var(--ex-ink-2); }
.rt-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: 10px; margin-bottom: 16px; }
.rt-card { border: 1px solid var(--ex-line); border-radius: 8px; padding: 10px 12px; background: var(--ex-bg-2, #fff); }
.rt-card-name { font-size: 12px; color: var(--ex-ink-2); margin-bottom: 4px; }
.rt-card-value { font-size: 18px; font-weight: 600; color: var(--ex-ink); }
.rt-card-unit { font-size: 12px; font-weight: 400; color: var(--ex-ink-2); margin-left: 2px; }
.rt-card-id { margin-top: 4px; font-size: 11px; font-family: 'Cascadia Mono', Consolas, monospace; color: var(--ex-ink-3); }
.hist-card { border-top: 1px solid var(--ex-line); padding-top: 14px; }
.hist-controls { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.hist-chart { height: 300px; }
.hist-empty { padding: 16px 0; text-align: center; font-size: 13px; color: var(--ex-ink-3); }
```

- [ ] **Step 4: 类型检查 + 前端测试**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vue-tsc --noEmit
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npm test
```

Expected: `vue-tsc` EXIT 0；vitest 全绿（含 Task 5 新增用例）。

- [ ] **Step 5: Commit**

```bash
git add :/frontend/src/views/Device.vue
git commit -m "feat(web): 设备详情抽屉 tabs——运行状态卡片 + TDengine 历史折线图/分页表格"
```

---

### Task 7: 验证（vue-tsc + vitest + 浏览器冒烟 + 报告）

全量验证 + Playwright 冒烟脚本 + 冒烟报告。

**Files:**
- Create: `test/smoke/smoke-device-center.mjs`
- Create: `docs/superpowers/2026-08-09-device-center-smoke.md`（冒烟报告）

**Interfaces:**
- Consumes: Task 1 造的 `st_prop_snd_ess_pcs` 数据（约 29+ 行）、Task 2/3/4 后端、Task 5/6 前端；本机服务（vite :25173、网关 :8000、TDengine :6041、Nacos）。
- Produces: 冒烟报告（PASS/FAIL 清单）。

- [ ] **Step 1: 全量单测 + 类型检查**

```bash
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npm test
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vue-tsc --noEmit
cd "D:/ProgramData/Codex-Data/Energy Storage IoT Platform/backend" && mvn -q -pl energy-tsdb,energy-product -am test
```

Expected: vitest 全绿、`vue-tsc` EXIT 0、两个后端模块 `BUILD SUCCESS`。

- [ ] **Step 2: 写冒烟脚本 `test/smoke/smoke-device-center.mjs`**

```js
// 子项目B 设备数据中心 — 浏览器冒烟
// 前置：TDengine 已造数（Task 1）；vite :25173 + 网关 :8000 运行。
// 依赖：playwright-core 可解析（本机已装）；Edge 无头。
import { chromium } from 'playwright-core'

const BASE = 'http://127.0.0.1:25173'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const DEVICE_NAME = 'sim-dev-000001'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let passed = 0, failed = 0
const fails = []
function ok(name, cond, detail = '') {
  if (cond) { passed++; console.log('PASS  ' + name) }
  else { failed++; fails.push(name + (detail ? ' — ' + detail : '')); console.log('FAIL  ' + name + (detail ? ' — ' + detail : '')) }
}

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await (await browser.newContext({ viewport: { width: 1600, height: 1000 } })).newPage()
page.setDefaultTimeout(15000)

try {
  // 1. 登录 → 设备管理
  await page.goto(`${BASE}/login`)
  await page.click('button:has-text("登 录")')
  await page.waitForURL(/dashboard|archive|ems|system|product|device|alarm|shadow|command/, { timeout: 20000 })
  await page.waitForSelector('.el-menu', { timeout: 10000 })
  ok('1. 登录成功进入主界面', true)
  await page.click('.el-menu-item:has-text("设备管理")')
  await page.waitForURL(/device/, { timeout: 10000 })
  await page.waitForSelector('.table-card', { timeout: 10000 })
  ok('2. 进入 /device 设备管理页', page.url().includes('/device'))

  // 2. 按名称搜索 sim-dev-000001 并打开详情
  await page.fill('.filter-card input[placeholder="设备名模糊"]', DEVICE_NAME)
  await page.click('button:has-text("查询")')
  await page.waitForSelector(`.el-table__row:has-text("${DEVICE_NAME}")`, { timeout: 10000 })
  ok('3. 按名称找到 sim-dev-000001', true)
  await page.locator(`.el-table__row:has-text("${DEVICE_NAME}")`).click()
  await page.waitForSelector('.el-drawer', { state: 'visible' })
  const box = await page.locator('.el-drawer').boundingBox()
  ok('4. 抽屉加宽至 820px', !!box && box.width >= 800, `width=${box?.width}`)
  const runtimeTab = page.locator('.el-tabs__item:has-text("运行状态")')
  ok('5. 出现「运行状态」tab', (await runtimeTab.count()) > 0)

  // 3. 切到运行状态 → 最新值卡片
  await runtimeTab.click()
  await page.waitForSelector('.rt-card', { timeout: 10000 })
  await page.waitForFunction(() => Array.from(document.querySelectorAll('.rt-card')).some((c) => c.textContent.includes('soc')), null, { timeout: 10000 })
  const socCard = await page.locator('.rt-card:has-text("soc")').textContent()
  ok('6. 卡片区含 soc 且值非 —', !!socCard && !socCard.includes('—'), socCard)
  const headText = await page.locator('.runtime-head').textContent()
  ok('7. 最后上报时间非空', /最后上报：\s*\d{2}-\d{2}/.test(headText), headText)

  // 4. 历史查询：默认近24h + 默认属性 → 折线图 + 表格
  await page.click('.hist-controls button:has-text("查询")')
  await page.waitForSelector('.hist-chart canvas', { timeout: 15000 })
  await page.waitForSelector('.hist-card .el-table__row', { timeout: 15000 })
  ok('8. 折线图 canvas 已渲染', (await page.locator('.hist-chart canvas').count()) > 0)
  const rowCount = await page.locator('.hist-card .el-table__row').count()
  ok('9. 数据表格 ≥1 行', rowCount >= 1, `rows=${rowCount}`)

  // 5. 翻页（总行数 > 20 时第 2 页仍有数据）
  const totalText = await page.locator('.hist-card .el-pagination__total').textContent()
  const totalMatch = totalText?.match(/\d+/)
  if (totalMatch && Number(totalMatch[0]) > 20) {
    await page.click('.hist-card .el-pager li:nth-child(2)')
    await page.waitForTimeout(600)
    const p2 = await page.locator('.hist-card .el-table__row').count()
    ok('10. 翻到第 2 页仍有数据', p2 >= 1, `rows=${p2}`)
  } else {
    ok('10. 翻页（总行数不足 20，跳过断言）', true)
  }

  // 6. 切到未来时间窗（无数据）→ 空态
  const fmt = (d) => { const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}` }
  const f1 = fmt(new Date(Date.now() + 3600000))
  const f2 = fmt(new Date(Date.now() + 7200000))
  const inputs = page.locator('.hist-controls .el-range-input')
  await inputs.nth(0).fill(f1)
  await inputs.nth(1).fill(f2)
  await inputs.nth(1).press('Enter')
  await page.click('.hist-controls button:has-text("查询")')
  await page.waitForSelector('.hist-empty', { timeout: 10000 })
  ok('11. 无数据时间窗显示空态', true)
} catch (e) {
  failed++; fails.push('脚本异常: ' + (e?.message || String(e)))
  console.log('ERROR ' + (e?.stack || e))
}

console.log(`\n=== ${passed} PASS / ${failed} FAIL ===`)
await browser.close()
process.exit(failed > 0 ? 1 : 0)
```

> 若运行环境 `playwright-core` 不可解析：`cd frontend && npm i -D playwright-core` 后改在该目录跑 `node ../test/smoke/smoke-device-center.mjs`（脚本无相对路径依赖，可任意 cwd）。

- [ ] **Step 3: 运行冒烟**

```bash
node test/smoke/smoke-device-center.mjs
```

Expected: 11 项全 PASS（`=== 11 PASS / 0 FAIL ===`）。前置确认：TDengine 已造数、vite/网关/Nacos 在跑、`sim-dev-000001` 在线（影子 reported 非空）。

- [ ] **Step 4: 写冒烟报告**

创建 `docs/superpowers/2026-08-09-device-center-smoke.md`，模板照子项目A `docs/.../smoke-archive-report.md`（日期/范围/环境/逐场景 PASS 表/说明/结论），结论含「与影子/TDengine 全链路一致」。

- [ ] **Step 5: Commit**

```bash
git add :/test/smoke/smoke-device-center.mjs :/docs/superpowers/2026-08-09-device-center-smoke.md
git commit -m "test(web): 子项目B 设备数据中心浏览器冒烟脚本 + 报告"
```

---

## Self-Review（writing-plans 自查）

**Spec 覆盖对照：**
- §5 TDengine 修复 + 造数 → Task 1（ALTER、10/20_stable.sql、seed_props.sh、sim-device 全链路核对）。
- §6.1 TSL by-key → Task 2（service/impl/controller + 单测）。
- §6.2 energy-tsdb 历史查询 → Task 3（builder + service + controller + DTO + 单测）；§6.3 网关路由 → Task 3 Step 10。
- §6.4 ShadowView.lastReportedTime → Task 4。
- §7.1 类型/API、§7.2 物模型解析工具 → Task 5（+vitest）；§7.3/7.4 Device.vue tabs + UI → Task 6。
- §8 数据契约、§9 空态/错误处理、§10 测试策略 → 各 Task 内联 + Task 7 冒烟。

**占位符扫描：** 所有步骤含完整代码/命令/预期；无 TBD/TODO/「类似 Task N」。

**类型一致性：**
- 后端 `getThingModelByProductKey(String): ThingModelView` ↔ 前端 `productApi.thingModelByKey(productKey): Promise<ThingModelView>`（Task 2/5/6 一致）。
- `GET /api/tsdb/property/history` 契约：后端 `PropertyHistoryView{deviceId,productKey,total(primitive long),records:[{ts(primitive long),values}]}` ↔ 前端 `PropertyHistoryView{deviceId,productKey,total:number,records:[{ts:number,values}]}`（Task 3/5/6 一致；`ts`/`total` 均数字）。
- `ShadowView.lastReportedTime` 后端 String ↔ 前端 `lastReportedTime?: string`（Task 4/5/6 一致）。
- `parseThingModel` 返回 `ThingModelSchema{properties:TsProperty[],...}`，`TsProperty.identifier/name/dataType/unit/accessMode` 与真实 TSL 属性条目一致（实测 `snd_ess_pcs` schema：`{name,unit,dataType,accessMode,identifier}`，runMode 另有 `enumValues`）。

**已知偏差（有意为之，实现时无需再问）：**
- spec §6.4 写 `String lastReportedTime`；实现以 `LocalDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)` 产出固定 19 位 ISO 字符串，wire 格式与前端 `string` 类型一致（避免 `LocalDateTime.toString()` 整秒省略 `:00` 的边界）。
- spec §6.2 说「identifiers 全被白名单过滤 → 400」：service 抛 `IllegalArgumentException`，controller 捕获转 `BusinessException(PARAM_INVALID)`（400）——不用裸 `ValidationException`（GlobalExceptionHandler 无对应 handler，会落 `Throwable`→500）。
- energy-tsdb 无需改 pom：`spring-boot-starter-web`/`validation` 由 energy-common 传递，`server.port:8112` 已配，`@RestController` 注册到既有 Tomcat。
- Task 2 `getThingModelByProductKey` 不调 `getThingModel(productId)`：后者内部 `requireProduct`→`getById` 会二次回源主键查询，且单测需额外 mock `selectById`；本路径已有 product，直接内联查 `thingModelMapper` 当前生效版本。
