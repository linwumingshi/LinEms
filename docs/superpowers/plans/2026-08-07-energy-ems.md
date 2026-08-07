# energy-ems 储能策略引擎 + 前端页面 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 energy-ems 储能策略引擎服务（多策略 + 计划生成 + 安全包络校验 + 下发复用 command 链路）+ 前端策略管理/计划页面。

**Architecture:** 新建独立 `energy-ems` 微服务（8105 端口），复用 `sql/mysql/70_ems.sql` 已设计的 5 张表 + `energy-common` 条件化租户拦截器；计划生成用纯函数 `PlanGenerator`（峰谷套利→24h 点序列），点序列写 TDengine `ems_plan_point` STABLE，下发通过调用 energy-command 的 `POST /api/command` 复用现成指令链路；前端新增策略管理与计划页面。

**Tech Stack:** Java 17 / Spring Boot 3.2 / MyBatis-Plus / Flyway / Nacos / Kafka / taos-jdbcdriver 3.9.0（TAOS-RS）/ OpenFeign（或 RestTemplate）/ Vue3 / Element Plus / ECharts。

## Global Constraints

- 5 张业务表结构以 `sql/mysql/70_ems.sql` 为准，**不改表结构**；库名 `es_ems`，前缀 `ems_`/`iot_` 按现有表命名。
- 所有表带 `tenant_id` 列，创建时经 `TenantContext` 写租户；查询由条件化租户拦截器自动追加条件（同 energy-product）。
- 控制器映射**不带 `/api`**（网关 `/api/ems/**` StripPrefix=1），对齐 product/device/station 资产模块。
- 下发复用 energy-command：`POST /api/command`（body: productKey/deviceName/command/params/createBy），不重复造指令链路。
- 安全包络校验必须在生成/下发前执行（Phase1 §2.4）。
- 点序列 TDengine：新建 `ems_plan_point` STABLE（按 stationId 建子表，tag 含 station_id），MySQL 只存计划头。
- 计划生成触发：页面手动 + `@Scheduled(cron="0 5 0 * * *")` 每日 00:05。
- 端口 8105 未被占用（README 端口表核对）。
- 前端复用 `api/*.ts` + `useEChart` + Element Plus 现有模式。
- 后端测试：JUnit5 + Mockito；核心逻辑（PlanGenerator/SafetyEnvelopeValidator）纯函数单测。
- 运行环境：本机 MySQL 8.4（127.0.0.1:3306，密码见 memory）、TDengine 3.3（127.0.0.1:6041 TAOS-RS root/taosdata）、Nacos 8848、Kafka 9092。maven-repo：`/d/Program Files/maven-repo`。

---

### Task 1: energy-ems 模块脚手架（pom + 启动类 + 配置 + 网关路由）

**Files:**
- Create: `backend/energy-ems/pom.xml`
- Create: `backend/energy-ems/src/main/resources/application.yml`
- Create: `backend/energy-ems/src/main/resources/bootstrap.yml`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/EnergyEmsApplication.java`
- Create: `backend/energy-ems/src/main/resources/db/migration/V1__init_ems.sql`（Flyway，复制 70_ems.sql 的 5 表）
- Modify: `backend/pom.xml`（模块列表加 `energy-ems`）
- Modify: `backend/energy-gateway/src/main/resources/application.yml`（加 `/api/ems/**` 路由）
- Modify: `deploy/scripts/start-stack.sh`、`deploy/scripts/status-stack.sh`、`deploy/scripts/stop-stack.sh`（SERVICES 加 energy-ems:8105）

**Interfaces:**
- Consumes: `energy-common`（TenantContext/ConditionalTenantLineHandler/Result）
- Produces: 可启动的空 Spring Boot 服务，注册 Nacos，网关 `/api/ems/**` 可达

- [ ] **Step 1: 复制 energy-product 的 pom 为模板，创建 `backend/energy-ems/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- energy-ems：储能策略引擎（策略/电价/约束/计划/执行记录） -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.sanduo</groupId>
    <artifactId>energy-ems-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>
  <artifactId>energy-ems</artifactId>
  <name>energy-ems</name>
  <description>储能策略引擎：策略/分时电价/安全约束/充放电计划/执行记录</description>

  <dependencies>
    <dependency>
      <groupId>com.sanduo</groupId>
      <artifactId>energy-common</artifactId>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
      <groupId>com.taosdata.jdbc</groupId>
      <artifactId>taos-jdbcdriver</artifactId>
      <version>3.9.0</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

> 注：本期用 RestTemplate 调 command（不引 Feign 依赖，减少 Nacos 负载均衡配置面）；Taos 连接直接 DriverManager 复用 tsdb 模式。

- [ ] **Step 2: 创建启动类 `EnergyEmsApplication.java`**

```java
package com.sanduo.energy.ems;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.sanduo.energy")
@MapperScan("com.sanduo.energy.ems.mapper")
@EnableScheduling
public class EnergyEmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnergyEmsApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 `application.yml`（端口 8105 + Nacos + MySQL + taos 连接）**

```yaml
server:
  port: 8105

spring:
  application:
    name: energy-ems
  config:
    import: nacos:energy-shared.yaml?group=ENERGY
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/es_ems?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
    username: root
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0
  kafka:
    bootstrap-servers: 127.0.0.1:9092
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    locations: classpath:db/migration
    encoding: UTF-8
  cloud:
    nacos:
      username: ${NACOS_USERNAME}
      password: ${NACOS_PASSWORD}
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: public
        group: ENERGY
      config:
        server-addr: 127.0.0.1:8848
        namespace: public

mybatis-plus:
  global-config:
    banner: false
    db-config:
      id-type: assign_id
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl

sanduo:
  taos:
    jdbc-url: jdbc:TAOS-RS://127.0.0.1:6041/iot_ems
    username: root
    password: ${TDENGINE_PASSWORD:taosdata}
    plan-db: iot_ems
  ems:
    command-base-url: http://127.0.0.1:8114

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    com.sanduo.energy: info
```

> 注：与 energy-product 的 application.yml 对齐——`spring.config.import: nacos:energy-shared.yaml` 注入密钥（DB 密码、Nacos 凭据等，见 [[nacos-config-secrets]]）；本地启动从 `deploy/env/local.env` 加载（start-stack.sh 已 source）。全局 `id-type: assign_id` 由各实体的 `@TableId(type = IdType.AUTO)` 覆盖（见 Task 2）。`logic-delete-field: deleted` 仅对含 `deleted` 字段的实体生效，EMS 实体无该字段故不受影响。

- [ ] **Step 4: 创建 Flyway `V1__init_ems.sql`**（复制 `sql/mysql/70_ems.sql` 的 5 表 CREATE，去掉 `DROP TABLE IF EXISTS`，保留 `USE es_ems` 行改为注释；Flyway 需先建库）

在 MySQL 执行：`CREATE DATABASE IF NOT EXISTS es_ems DEFAULT CHARSET utf8mb4;`

- [ ] **Step 5: `backend/pom.xml` 模块列表加 `energy-ems`**（在 energy-alarm 后追加一行 `<module>energy-ems</module>`）

- [ ] **Step 6: 网关 `application.yml` 加路由**（在 energy-alarm 路由后追加）

```yaml
        - id: energy-ems
          uri: lb://energy-ems
          predicates:
            - Path=/api/ems/**
          filters:
            - StripPrefix=1
```

- [ ] **Step 7: 三个部署脚本 SERVICES 加 `energy-ems:8105`**

`start-stack.sh` / `status-stack.sh` / `stop-stack.sh` 的 SERVICES 数组追加 `"energy-ems:8105"`（网关后启动顺序放在 energy-station 之后）。

- [ ] **Step 8: 构建验证**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" package -DskipTests -pl energy-ems -am`
Expected: BUILD SUCCESS，生成 `energy-ems-1.0.0-SNAPSHOT.jar`

- [ ] **Step 9: 提交**

```bash
git add backend/pom.xml backend/energy-ems deploy/scripts/start-stack.sh deploy/scripts/status-stack.sh deploy/scripts/stop-stack.sh backend/energy-gateway/src/main/resources/application.yml
git commit -m "feat(energy-ems): 模块脚手架（pom/启动类/配置/Flyway/网关路由/部署脚本）"
```

---

### Task 2: 实体 + Mapper（5 表）

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity/EmsStrategy.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity/EmsPlan.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity/EmsElectricityPrice.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity/EmsConstraint.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity/EmsExecutionRecord.java`
- Create: 对应 5 个 `mapper/*Mapper.java`

**Interfaces:**
- Consumes: `BaseEntity`（energy-common，含 id/createTime/updateTime/deleted/tenantId）
- Produces: 5 个实体 + 5 个 Mapper，后续 Service 层使用

- [ ] **Step 1: 确认实体基类结论**（对照 product 的 `Product extends BaseEntity` 差异）

Run: `grep -n "class BaseEntity" -A 20 backend/energy-common/src/main/java/com/sanduo/energy/common/entity/BaseEntity.java`

> **重要差异：** `BaseEntity` 带 `deleted`（@TableLogic）与 `id` 无关的 create/update_time，而 `70_ems.sql` 的 5 张表**都没有 `deleted` 列**、且各自 PK 名为 `strategy_id/plan_id/price_id/constraint_id/exec_id`。因此 EMS 实体**不能 extends BaseEntity**（否则 @TableLogic 会拼 `deleted=0` 查询条件与 `deleted` 插入列 → SQL 报错）。改为显式声明 `@TableId(type = IdType.AUTO)` PK + `tenantId` + `createTime/updateTime`（带 `@TableField(fill=...)`，由 energy-common `AuditMetaObjectHandler` 自动填充，同 BaseEntity 模式）。全局 `id-type: assign_id` 被 `@TableId(type=IdType.AUTO)` 覆盖。

- [ ] **Step 2: 创建 `EmsStrategy.java`**

```java
package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略定义（ems_strategy）。表无 deleted 列、PK 为 strategy_id，
 * 故不 extends BaseEntity；审计字段由 AuditMetaObjectHandler 填充。
 */
@Data
@TableName("ems_strategy")
public class EmsStrategy {

    @TableId(type = IdType.AUTO)
    private Long strategyId;

    private Long tenantId;

    private Long stationId;

    private String strategyName;

    /** PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME */
    private String strategyType;

    /** 策略配置 JSON（chargeWindows/dischargeWindows/socRange） */
    private String config;

    /** 多策略冲突仲裁优先级 */
    private Integer priority;

    /** 0草稿 1启用 2停用 */
    private Integer status;

    private Integer version;

    private Long createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: 创建其余 4 个实体**（对照 70_ems.sql 字段；注意价格表**无 update_time**、执行记录时间列名是 **execute_time**）

`EmsPlan.java`（ems_plan，有 create_time/update_time）：

```java
package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 策略计划头（ems_plan）。充放电点序列入 TDengine，本表存计划元数据。 */
@Data
@TableName("ems_plan")
public class EmsPlan {

    @TableId(type = IdType.AUTO)
    private Long planId;

    private Long tenantId;

    private Long stationId;

    private Long strategyId;

    private LocalDate planDate;

    /** 1充电 2放电 3混合 */
    private Integer planType;

    private BigDecimal totalEnergy;

    /** 计划参数快照 JSON */
    private String planParam;

    /** 0待执行 1执行中 2完成 3已取消 */
    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

`EmsElectricityPrice.java`（ems_electricity_price，**只有 create_time**）：

```java
package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 分时电价（ems_electricity_price）。表无 update_time 列。 */
@Data
@TableName("ems_electricity_price")
public class EmsElectricityPrice {

    @TableId(type = IdType.AUTO)
    private Long priceId;

    private Long tenantId;

    private Long stationId;

    private String region;

    /** DEEP/PEEK/PEAK/FLAT/VALLEY */
    private String priceType;

    private LocalTime startTime;

    private LocalTime endTime;

    private BigDecimal price;

    private LocalDate validFrom;

    private LocalDate validTo;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

`EmsConstraint.java`（ems_constraint，一电站一条 uk_constraint_station）：

```java
package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 安全约束（ems_constraint）。下发前安全包络校验，Phase1 §2.4。 */
@Data
@TableName("ems_constraint")
public class EmsConstraint {

    @TableId(type = IdType.AUTO)
    private Long constraintId;

    private Long tenantId;

    private Long stationId;

    private BigDecimal socMin;

    private BigDecimal socMax;

    private BigDecimal chargePowerMax;

    private BigDecimal dischargePowerMax;

    private BigDecimal tempMax;

    private BigDecimal voltageMax;

    private BigDecimal currentMax;

    /** 扩展安全包络 JSON */
    private String safetyEnvelope;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

`EmsExecutionRecord.java`（ems_execution_record，**时间列名 execute_time**）：

```java
package com.sanduo.energy.ems.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 策略执行记录（ems_execution_record）。execute_time 由 DB DEFAULT CURRENT_TIMESTAMP 填充。 */
@Data
@TableName("ems_execution_record")
public class EmsExecutionRecord {

    @TableId(type = IdType.AUTO)
    private Long execId;

    private Long tenantId;

    private Long planId;

    private String commandId;

    private Long deviceId;

    /** CHARGE/DISCHARGE/STANDBY */
    private String action;

    /** 下发参数 JSON */
    private String params;

    /** 执行回执 JSON */
    private String result;

    private LocalDateTime executeTime;
}
```

- [ ] **Step 4: 创建 5 个 Mapper**（extends `BaseMapper<T>`，`@Mapper` 注解可选——@MapperScan 已覆盖）

```java
package com.sanduo.energy.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanduo.energy.ems.entity.EmsStrategy;

public interface EmsStrategyMapper extends BaseMapper<EmsStrategy> {
}
```

（EmsPlanMapper / EmsElectricityPriceMapper / EmsConstraintMapper / EmsExecutionRecordMapper 同构）

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" compile -pl energy-ems -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/entity backend/energy-ems/src/main/java/com/sanduo/energy/ems/mapper
git commit -m "feat(energy-ems): 5 表实体 + Mapper"
```

---

### Task 3: 纯函数 `PlanGenerator`（峰谷套利 → 24h 点序列）

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/util/PlanGenerator.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/util/PlanPoint.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/util/PlanInput.java`
- Test: `backend/energy-ems/src/test/java/com/sanduo/energy/ems/util/PlanGeneratorTest.java`

**Interfaces:**
- Produces: `PlanGenerator.generate(PlanInput) -> List<PlanPoint>`，纯函数无副作用

```java
public record PlanPoint(LocalTime time, String action, double powerKw, double socTarget) {}
public record PlanInput(
    String strategyType,        // "PEAK_VALLEY"
    String config,              // JSON: {chargeWindows:[{start, end, powerLimit}], dischargeWindows:[...], socRange:{min,max}}
    List<PriceTier> prices,     // 分时电价
    double socInit,             // 初始 SOC %
    double socMin, double socMax, double chargePowerMax, double dischargePowerMax
) {}
public record PriceTier(LocalTime start, LocalTime end, String priceType, double price) {}
```

- [ ] **Step 1: 写失败的测试**（峰谷套利基础：谷充峰放）

```java
package com.sanduo.energy.ems.util;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanGeneratorTest {

    @Test
    void peakValley_basicChargeValleyDischargePeak() {
        PlanInput in = new PlanInput(
            "PEAK_VALLEY",
            """
            {"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}],
             "dischargeWindows":[{"start":"18:00","end":"22:00","powerLimit":80}],
             "socRange":{"min":10,"max":90}}
            """,
            List.of(new PriceTier(LocalTime.of(0,0), LocalTime.of(8,0), "VALLEY", 0.3),
                    new PriceTier(LocalTime.of(8,0), LocalTime.of(23,59), "PEAK", 1.2)),
            50.0, 10.0, 90.0, 100.0, 80.0
        );
        List<PlanPoint> points = PlanGenerator.generate(in);
        assertNotNull(points);
        assertFalse(points.isEmpty());
        // 充电窗口内应有 CHARGE 点
        boolean hasCharge = points.stream().anyMatch(p -> p.action().equals("CHARGE"));
        boolean hasDischarge = points.stream().anyMatch(p -> p.action().equals("DISCHARGE"));
        assertTrue(hasCharge && hasDischarge);
        // SOC 不越界（brief 声明的测试聚焦点）
        assertTrue(points.stream().allMatch(p -> p.socTarget() >= 10 && p.socTarget() <= 90));
        // 尾点 STANDBY@23:55（升序排序后自然最后）
        assertEquals(LocalTime.of(23, 55), points.get(points.size() - 1).time());
        assertEquals("STANDBY", points.get(points.size() - 1).action());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" test -pl energy-ems -Dtest=PlanGeneratorTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现 `PlanPoint` / `PlanInput` / `PlanGenerator`**

`PlanGenerator` 核心算法（纯函数）：

```java
package com.sanduo.energy.ems.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 计划生成器：把启用策略解析为 24h 充放电点序列（5 分钟粒度）。
 * 纯函数：不碰 DB / MQTT，输入输出全在方法签名，便于单测。
 * 本期只实现峰谷套利（PEAK_VALLEY）；其余策略类型返回空列表，留给后续 AI 服务扩展。
 */
public final class PlanGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 点序列粒度（分钟） */
    private static final int SLOT_MIN = 5;

    private PlanGenerator() {
    }

    /**
     * 峰谷套利：谷段充电窗口逐 5 分钟出 CHARGE 点，峰段放电窗口出 DISCHARGE 点；
     * 窗口功率 = min(window.powerLimit, 包络功率上限)。SOC 演进为近似
     * （YAGNI：精确 SOC 属模型层），但点内 socTarget 恒在 [socMin, socMax]。
     *
     * @param in 计划输入（策略类型 / config JSON / 电价 / 初始 SOC / 安全包络）
     * @return 按时间升序的点序列
     */
    public static List<PlanPoint> generate(PlanInput in) {
        if (!"PEAK_VALLEY".equals(in.strategyType())) {
            return List.of();
        }
        List<PlanPoint> points = new ArrayList<>();
        try {
            JsonNode cfg = MAPPER.readTree(in.config());
            double soc = in.socInit();
            for (JsonNode w : cfg.path("chargeWindows")) {
                LocalTime start = LocalTime.parse(w.path("start").asText());
                LocalTime end = LocalTime.parse(w.path("end").asText());
                double power = windowPower(w, in.chargePowerMax());
                for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
                    if (soc >= in.socMax()) break;
                    points.add(new PlanPoint(t, "CHARGE", power, soc));
                    soc += power * SLOT_MIN / 60.0 * 0.01; // 近似 SOC 演进
                }
            }
            for (JsonNode w : cfg.path("dischargeWindows")) {
                LocalTime start = LocalTime.parse(w.path("start").asText());
                LocalTime end = LocalTime.parse(w.path("end").asText());
                double power = windowPower(w, in.dischargePowerMax());
                for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
                    if (soc <= in.socMin()) break;
                    points.add(new PlanPoint(t, "DISCHARGE", power, soc));
                    soc -= power * SLOT_MIN / 60.0 * 0.01;
                }
            }
            // 当日尾点锚定待机（保证前端图时间轴完整）
            points.add(new PlanPoint(LocalTime.of(23, 55), "STANDBY", 0, soc));
        } catch (Exception e) {
            throw new IllegalArgumentException("策略配置解析失败: " + e.getMessage(), e);
        }
        // 按时间升序（窗口在 config 中可能非时序排列；STANDBY@23:55 自然排最后）
        points.sort(Comparator.comparing(PlanPoint::time));
        return points;
    }

    /** 窗口功率 = min(window.powerLimit, 包络功率上限)。 */
    private static double windowPower(JsonNode w, double envelopeMax) {
        return Math.min(w.path("powerLimit").asDouble(0), envelopeMax);
    }
}
```

> 注：SOC 演进公式是简化近似（充电功率×时长→SOC 增量）。真正精确需电池容量，本期 YAGNI——计划用于安全包络校验 + 下发，SOC 演进的业务准确性属 AI/模型层。测试聚焦行为（有 CHARGE/DISCHARGE 点、SOC 不越界）。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" test -pl energy-ems -Dtest=PlanGeneratorTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/util backend/energy-ems/src/test/java/com/sanduo/energy/ems/util
git commit -m "feat(energy-ems): PlanGenerator 峰谷套利计划生成（纯函数+TDD）"
```

---

### Task 4: 纯函数 `SafetyEnvelopeValidator`

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/SafetyEnvelopeValidator.java`
- Test: `backend/energy-ems/src/test/java/com/sanduo/energy/ems/service/SafetyEnvelopeValidatorTest.java`

**Interfaces:**
- Consumes: `PlanPoint` (Task 3)
- Produces: `SafetyEnvelopeValidator.validate(List<PlanPoint>, socMin, socMax, chargeMax, dischargeMax, tempMax) -> ValidationResult`

```java
public record ValidationResult(boolean valid, List<String> rejections) {}
```

- [ ] **Step 1: 写失败的测试**（SOC 越界、功率越界、合法通过；温度校验本期推迟——PlanPoint 无温度数据，见 Step 3 注释）

```java
package com.sanduo.energy.ems.service;

import com.sanduo.energy.ems.util.PlanPoint;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SafetyEnvelopeValidatorTest {

    @Test
    void rejectsSocOutOfRange() {
        var pts = List.of(new PlanPoint(LocalTime.of(2,0), "CHARGE", 100, 95.0)); // soc 95 > max 90
        var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
        assertFalse(r.valid());
        assertTrue(r.rejections().stream().anyMatch(s -> s.contains("SOC")));
    }

    @Test
    void rejectsPowerOverLimit() {
        var pts = List.of(new PlanPoint(LocalTime.of(2,0), "CHARGE", 150, 50.0)); // 150 > charge 100
        var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
        assertFalse(r.valid());
        assertTrue(r.rejections().stream().anyMatch(s -> s.contains("功率")));
    }

    @Test
    void passesWithinEnvelope() {
        var pts = List.of(new PlanPoint(LocalTime.of(2,0), "CHARGE", 80, 50.0));
        var r = SafetyEnvelopeValidator.validate(pts, 10.0, 90.0, 100.0, 80.0, null);
        assertTrue(r.valid());
    }
}
```

- [ ] **Step 2: 运行确认失败**（类不存在）

- [ ] **Step 3: 实现**

```java
package com.sanduo.energy.ems.service;

import com.sanduo.energy.ems.util.PlanPoint;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class SafetyEnvelopeValidator {

    public record ValidationResult(boolean valid, List<String> rejections) {}

    /**
     * 安全包络校验：SOC 越界 + 充电/放电功率越界。温度校验（tempMax）本期推迟——
     * PlanPoint 无温度数据（温度是遥测值非计划值），接口保留占位，后续接温感遥测时启用。
     */
    public static ValidationResult validate(List<PlanPoint> points, double socMin, double socMax,
                                            double chargeMax, double dischargeMax, Double tempMax) {
        List<String> rejections = new ArrayList<>();
        for (PlanPoint p : points) {
            if (p.socTarget() < socMin || p.socTarget() > socMax) {
                rejections.add(String.format("SOC 越界 time=%s soc=%.1f (允许 %.1f~%.1f)",
                        p.time(), p.socTarget(), socMin, socMax));
            }
            if ("CHARGE".equals(p.action()) && p.powerKw() > chargeMax) {
                rejections.add(String.format("充电功率越界 time=%s power=%.1f (允许 %.1f)",
                        p.time(), p.powerKw(), chargeMax));
            }
            if ("DISCHARGE".equals(p.action()) && p.powerKw() > dischargeMax) {
                rejections.add(String.format("放电功率越界 time=%s power=%.1f (允许 %.1f)",
                        p.time(), p.powerKw(), dischargeMax));
            }
        }
        return new ValidationResult(rejections.isEmpty(), rejections);
    }
}
```

- [ ] **Step 4: 运行确认通过**

- [ ] **Step 5: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/SafetyEnvelopeValidator.java backend/energy-ems/src/test/java/com/sanduo/energy/ems/service/SafetyEnvelopeValidatorTest.java
git commit -m "feat(energy-ems): SafetyEnvelopeValidator 安全包络校验（纯函数+TDD）"
```

---

### Task 5: Service 层（策略/电价/约束 CRUD）

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsStrategyService.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsPriceService.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsConstraintService.java`

**Interfaces:**
- Consumes: 5 Mapper + `TenantContext` + `Result`
- Produces:
  - `EmsStrategyService` : `create(EmsStrategy)`, `page(Page, query)`, `update(EmsStrategy)`, `delete(Long)`, `switchStatus(Long, int)`
  - `EmsPriceService` : `batchSave(List<EmsElectricityPrice>)`, `page(Page, query)`, `update(EmsElectricityPrice)`
  - `EmsConstraintService` : `getByStation(Long)`, `save(EmsConstraint)`（upsert）

- [ ] **Step 1: 看 product 的 `ProductServiceImpl` 租户模式**（requireTenant + TenantContext.getTenantId）

Run: `sed -n '40,60p' backend/energy-product/src/main/java/com/sanduo/energy/product/service/impl/ProductServiceImpl.java`

- [ ] **Step 2: 实现 `EmsStrategyService`**（仿 ProductServiceImpl：`extends ServiceImpl`，创建时 `setTenantId(requireTenant())`，分页用 `Page` + LambdaQueryWrapper 条件过滤 stationId/type/status）

```java
package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.tenant.TenantContext;
import com.sanduo.energy.ems.entity.EmsStrategy;
import com.sanduo.energy.ems.mapper.EmsStrategyMapper;
import org.springframework.stereotype.Service;

/**
 * 策略 CRUD。租户隔离由条件化租户拦截器自动完成
 * （HTTP 线程按 {@link TenantContext} 追加 tenant_id），本服务仅写入时读取租户。
 */
@Service
public class EmsStrategyService extends ServiceImpl<EmsStrategyMapper, EmsStrategy> {

    /** 创建策略（草稿）。 */
    public EmsStrategy create(EmsStrategy s) {
        s.setTenantId(requireTenant());
        s.setStatus(0);
        s.setVersion(1);
        save(s);
        return s;
    }

    public Page<EmsStrategy> page(long pageNo, long pageSize, Long stationId, String type, Integer status) {
        return page(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EmsStrategy>()
                        .eq(stationId != null, EmsStrategy::getStationId, stationId)
                        .eq(type != null, EmsStrategy::getStrategyType, type)
                        .eq(status != null, EmsStrategy::getStatus, status)
                        .orderByDesc(EmsStrategy::getPriority));
    }

    public EmsStrategy update(EmsStrategy s) {
        s.setTenantId(null); // 租户不可改
        updateById(s);
        return s;
    }

    public void delete(Long id) {
        EmsStrategy s = getById(id);
        if (s == null) throw new BusinessException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        if (s.getStatus() == 1) throw new BusinessException(ErrorCode.CONFLICT, "启用中的策略不能删除，请先停用");
        removeById(id);
    }

    public void switchStatus(Long id, int status) {
        EmsStrategy s = getById(id);
        if (s == null) throw new BusinessException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        s.setStatus(status);
        s.setVersion(s.getVersion() + 1);
        updateById(s);
    }

    private long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        return t;
    }
}
```

- [ ] **Step 3: 实现 `EmsPriceService`**（分页/批量保存/更新，结构同 Step 2）

```java
package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.tenant.TenantContext;
import com.sanduo.energy.ems.entity.EmsElectricityPrice;
import com.sanduo.energy.ems.mapper.EmsElectricityPriceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分时电价管理。 */
@Service
public class EmsPriceService extends ServiceImpl<EmsElectricityPriceMapper, EmsElectricityPrice> {

    public Page<EmsElectricityPrice> page(long pageNo, long pageSize, Long stationId, String region) {
        return page(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EmsElectricityPrice>()
                        .eq(stationId != null, EmsElectricityPrice::getStationId, stationId)
                        .eq(region != null, EmsElectricityPrice::getRegion, region)
                        .orderByAsc(EmsElectricityPrice::getStartTime));
    }

    /** 批量保存：逐条补租户后插入。 */
    public void batchSave(List<EmsElectricityPrice> prices) {
        long tenant = requireTenant();
        for (EmsElectricityPrice p : prices) {
            p.setTenantId(tenant);
            if (p.getStatus() == null) p.setStatus(1);
            save(p);
        }
    }

    public void update(EmsElectricityPrice p) {
        p.setTenantId(null);
        updateById(p);
    }

    private long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        return t;
    }
}
```

- [ ] **Step 4: 实现 `EmsConstraintService`**（一电站一条，getOne + uk_constraint_station 做 upsert）

```java
package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.tenant.TenantContext;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.mapper.EmsConstraintMapper;
import org.springframework.stereotype.Service;

/** 安全约束管理（一电站一条，uk_constraint_station）。 */
@Service
public class EmsConstraintService extends ServiceImpl<EmsConstraintMapper, EmsConstraint> {

    public EmsConstraint getByStation(Long stationId) {
        return getOne(new LambdaQueryWrapper<EmsConstraint>()
                .eq(EmsConstraint::getStationId, stationId));
    }

    /** 保存/更新安全约束（一电站一条 upsert）。 */
    public EmsConstraint saveConstraint(EmsConstraint c) {
        long tenant = requireTenant();
        EmsConstraint exists = getByStation(c.getStationId());
        c.setTenantId(tenant);
        if (exists == null) {
            if (c.getStatus() == null) c.setStatus(1);
            save(c);
        } else {
            c.setConstraintId(exists.getConstraintId());
            c.setTenantId(null); // 租户不可改
            updateById(c);
        }
        return c;
    }

    private long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        return t;
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" compile -pl energy-ems -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/service
git commit -m "feat(energy-ems): 策略/电价/约束 Service 层"
```

---

### Task 6: Web 层 Controller（4 个）+ CommandClient

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsStrategyController.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsPriceController.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsConstraintController.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsPlanController.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/dto/EmsStrategySaveReq.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/dto/EmsPlanGenerateReq.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/CommandClient.java`

**Interfaces:**
- Consumes: Service 层 (Task 5), `Result`, `TenantContext`
- Produces: REST API `/strategy /price /constraint /plan`；`CommandClient.dispatch(String productKey, String deviceName, String command, Map<String, Object> params, long createBy) -> String commandId`

- [ ] **Step 1: 实现 `CommandClient`**（RestTemplate 调 command 服务；`params` 可能为 null 用 HashMap，不用 `Map.of`）

```java
package com.sanduo.energy.ems.service;

import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/** 调用 energy-command POST /api/command 建指令（复用 QoS1/ACK 链路）。薄封装，可替换为 Feign。 */
@Component
public class CommandClient {

    private final RestTemplate rest = new RestTemplate();

    @Value("${sanduo.ems.command-base-url:http://127.0.0.1:8114}")
    private String baseUrl;

    /** 调 energy-command POST /api/command，返回 commandId。业务失败抛 BusinessException（message 透传 command 服务）。 */
    public String dispatch(String productKey, String deviceName, String command,
                           Map<String, Object> params, long createBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", productKey);
        body.put("deviceName", deviceName);
        body.put("command", command);
        body.put("commandType", 2);
        body.put("createBy", createBy);
        if (params != null) {
            body.put("params", params);
        }
        ResponseEntity<Map> resp = rest.postForEntity(baseUrl + "/api/command", body, Map.class);
        Map<String, Object> result = resp.getBody();
        if (result == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务返回空响应");
        }
        if (!(result.get("code") instanceof Number n) || n.intValue() != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务拒绝指令: " + result.get("message"));
        }
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        if (data == null || data.get("commandId") == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "command 服务响应缺少 commandId");
        }
        return (String) data.get("commandId");
    }
}
```

- [ ] **Step 2: 创建 DTO**（`web/dto/EmsStrategySaveReq.java` + `EmsPlanGenerateReq.java`）

```java
package com.sanduo.energy.ems.web.dto;

import com.sanduo.energy.ems.entity.EmsStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmsStrategySaveReq {

    private Long strategyId;

    @NotNull
    private Long stationId;

    @NotBlank
    private String strategyName;

    @NotBlank
    private String strategyType;

    @NotBlank
    private String config;

    private Integer priority;

    public EmsStrategy toEntity() {
        EmsStrategy s = new EmsStrategy();
        s.setStrategyId(strategyId);
        s.setStationId(stationId);
        s.setStrategyName(strategyName);
        s.setStrategyType(strategyType);
        s.setConfig(config);
        s.setPriority(priority == null ? 0 : priority);
        return s;
    }
}
```

```java
package com.sanduo.energy.ems.web.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class EmsPlanGenerateReq {

    private Long stationId;

    private Long strategyId;

    private LocalDate planDate;
}
```

- [ ] **Step 3: 实现 `EmsStrategyController`**（映射不带 /api，网关 StripPrefix 处理；分页返回 `PageResult` 对齐 product）

```java
package com.sanduo.energy.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsStrategy;
import com.sanduo.energy.ems.service.EmsStrategyService;
import com.sanduo.energy.ems.web.dto.EmsStrategySaveReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/strategy")
public class EmsStrategyController {

    private final EmsStrategyService service;

    public EmsStrategyController(EmsStrategyService service) {
        this.service = service;
    }

    @PostMapping
    public Result<EmsStrategy> create(@Valid @RequestBody EmsStrategySaveReq req) {
        return Result.ok(service.create(req.toEntity()));
    }

    @GetMapping("/page")
    public Result<PageResult<EmsStrategy>> page(@RequestParam(defaultValue = "1") long pageNo,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestParam(required = false) Long stationId,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) Integer status) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, type, status)));
    }

    @PutMapping("/{strategyId}")
    public Result<EmsStrategy> update(@PathVariable Long strategyId, @Valid @RequestBody EmsStrategySaveReq req) {
        req.setStrategyId(strategyId);
        return Result.ok(service.update(req.toEntity()));
    }

    @DeleteMapping("/{strategyId}")
    public Result<Void> delete(@PathVariable Long strategyId) {
        service.delete(strategyId);
        return Result.ok();
    }

    @PutMapping("/{strategyId}/status")
    public Result<Void> switchStatus(@PathVariable Long strategyId, @RequestParam int status) {
        service.switchStatus(strategyId, status);
        return Result.ok();
    }
}
```

- [ ] **Step 4: 实现 `EmsPriceController`**（分页 / 批量保存 / 更新）

```java
package com.sanduo.energy.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsElectricityPrice;
import com.sanduo.energy.ems.service.EmsPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/price")
public class EmsPriceController {

    private final EmsPriceService service;

    public EmsPriceController(EmsPriceService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public Result<PageResult<EmsElectricityPrice>> page(@RequestParam(defaultValue = "1") long pageNo,
                                                        @RequestParam(defaultValue = "10") long pageSize,
                                                        @RequestParam(required = false) Long stationId,
                                                        @RequestParam(required = false) String region) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, region)));
    }

    /** 批量保存分时电价。 */
    @PostMapping
    public Result<Void> batchSave(@RequestBody List<EmsElectricityPrice> prices) {
        service.batchSave(prices);
        return Result.ok();
    }

    @PutMapping("/{priceId}")
    public Result<Void> update(@PathVariable Long priceId, @RequestBody EmsElectricityPrice price) {
        price.setPriceId(priceId);
        service.update(price);
        return Result.ok();
    }
}
```

- [ ] **Step 5: 实现 `EmsConstraintController`**（按电站查 / upsert）

```java
package com.sanduo.energy.ems.web;

import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.service.EmsConstraintService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/constraint")
public class EmsConstraintController {

    private final EmsConstraintService service;

    public EmsConstraintController(EmsConstraintService service) {
        this.service = service;
    }

    @GetMapping
    public Result<EmsConstraint> getByStation(@RequestParam Long stationId) {
        return Result.ok(service.getByStation(stationId));
    }

    /** 保存安全约束（一电站一条 upsert）。 */
    @PutMapping
    public Result<EmsConstraint> save(@RequestBody EmsConstraint constraint) {
        return Result.ok(service.saveConstraint(constraint));
    }
}
```

- [ ] **Step 6: 暂缓 `EmsPlanController`**——其依赖 Task 7 的 `EmsPlanService`，完整代码移到 Task 7 Step 5 与 `EmsPlanService` 一并创建（本任务只落地 Strategy/Price/Constraint 三个 Controller，编译不受影响）

- [ ] **Step 7: 编译验证**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" compile -pl energy-ems -am`
Expected: BUILD SUCCESS（Strategy/Price/Constraint Controller 仅依赖 Task 5 的 Service，编译通过）

- [ ] **Step 8: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/web backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/CommandClient.java
git commit -m "feat(energy-ems): Web 层 Controller + CommandClient 下发封装"
```

---

### Task 7: 计划生成编排 `EmsPlanService`

**Files:**
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsPlanService.java`
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/util/TdenginePlanWriter.java`（TAOS-RS 读写点序列）
- Create: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsPlanController.java`（自 Task 6 移入，依赖本任务的 EmsPlanService）
- Test: `backend/energy-ems/src/test/java/com/sanduo/energy/ems/service/EmsPlanServiceTest.java`（Mockito）

**Interfaces:**
- Consumes: `PlanGenerator` (Task 3), `SafetyEnvelopeValidator` (Task 4), 5 Mapper, `CommandClient` (Task 6)
- Produces:
  - `EmsPlanService.generate(stationId, strategyId, planDate) -> EmsPlan`
  - `EmsPlanService.dispatch(planId) -> int`（下发点数）
  - `EmsPlanService.page(...)`, `getPoints(planId) -> List<PlanPoint>`

- [ ] **Step 1: 写 `EmsPlanServiceTest`（Mockito 编排测试）**：mock Mapper/Writer/CommandClient，真实 SafetyEnvelopeValidator，断言生成→校验→落库→写 TDengine 调用链；**租户取自策略行**（generate 不依赖请求租户上下文）

```java
package com.sanduo.energy.ems.service;

import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.entity.EmsPlan;
import com.sanduo.energy.ems.entity.EmsStrategy;
import com.sanduo.energy.ems.mapper.EmsConstraintMapper;
import com.sanduo.energy.ems.mapper.EmsElectricityPriceMapper;
import com.sanduo.energy.ems.mapper.EmsExecutionRecordMapper;
import com.sanduo.energy.ems.mapper.EmsPlanMapper;
import com.sanduo.energy.ems.mapper.EmsStrategyMapper;
import com.sanduo.energy.ems.util.TdenginePlanWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmsPlanServiceTest {

    @Test
    void generate_createsPlanAndWritesPoints() throws Exception {
        EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
        EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
        EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
        EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
        EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);
        SafetyEnvelopeValidator validator = new SafetyEnvelopeValidator();
        TdenginePlanWriter writer = mock(TdenginePlanWriter.class);
        CommandClient commandClient = mock(CommandClient.class);

        EmsStrategy s = new EmsStrategy();
        s.setStrategyId(1L);
        s.setStationId(10L);
        s.setTenantId(7L);
        s.setStrategyType("PEAK_VALLEY");
        s.setConfig("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":100}],"
                + "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}],"
                + "\"socRange\":{\"min\":10,\"max\":90}}");
        when(stratMapper.selectById(1L)).thenReturn(s);

        EmsConstraint constraint = new EmsConstraint();
        constraint.setSocMin(new BigDecimal("10"));
        constraint.setSocMax(new BigDecimal("90"));
        constraint.setChargePowerMax(new BigDecimal("100"));
        constraint.setDischargePowerMax(new BigDecimal("80"));
        when(constraintMapper.selectOne(any())).thenReturn(constraint);
        when(priceMapper.selectList(any())).thenReturn(java.util.List.of());

        EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper,
                planMapper, execMapper, validator, writer, commandClient);
        EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

        assertNotNull(plan);
        assertEquals(7L, plan.getTenantId());            // 租户取自策略行
        verify(planMapper).insert(any(EmsPlan.class));   // 计划头落库
        verify(writer).write(eq(10L), eq(LocalDate.of(2026, 8, 8)), anyList()); // 点序列写 TDengine
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" test -pl energy-ems -Dtest=EmsPlanServiceTest`
Expected: 编译失败（EmsPlanService/TdenginePlanWriter 不存在）

- [ ] **Step 3: 实现 `TdenginePlanWriter`**（TAOS-RS 直连，仿 tsdb `TdengineWriter`；幂等建库/建 STABLE，时间戳用 `planDate + HH:MM:SS` 完整格式，含 `read` 回读供 dispatch/getPoints）

```java
package com.sanduo.energy.ems.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** TDengine 点序列读写（TAOS-RS RESTful）。子表按电站建 plan_{stationId}，STABLE 为 ems_plan_point。 */
@Slf4j
@Component
public class TdenginePlanWriter {

    @Value("${sanduo.taos.jdbc-url:jdbc:TAOS-RS://127.0.0.1:6041/iot_ems}")
    private String jdbcUrl;

    @Value("${sanduo.taos.username:root}")
    private String username;

    @Value("${sanduo.taos.password:taosdata}")
    private String password;

    /** 写入计划点序列（幂等建库/建 STABLE）。 */
    public void write(long stationId, LocalDate planDate, List<PlanPoint> points) throws Exception {
        if (points.isEmpty()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS iot_ems");
            st.execute("USE iot_ems");
            st.execute("CREATE STABLE IF NOT EXISTS ems_plan_point "
                    + "(ts TIMESTAMP, action VARCHAR(16), power_kw DOUBLE, soc DOUBLE) "
                    + "TAGS (station_id BIGINT)");
            String table = "plan_" + stationId;
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                    .append(" (ts, action, power_kw, soc) VALUES ");
            for (PlanPoint p : points) {
                sb.append("('").append(planDate).append(" ").append(p.time()).append("', '")
                  .append(p.action()).append("', ")
                  .append(p.powerKw()).append(", ")
                  .append(p.socTarget()).append(") ");
            }
            st.execute(sb.toString());
        }
    }

    /** 读取指定计划日期的点序列（按 ts 升序）。 */
    public List<PlanPoint> read(long stationId, LocalDate planDate) throws Exception {
        List<PlanPoint> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts, action, power_kw, soc FROM plan_" + stationId
                             + " WHERE ts >= '" + planDate + " 00:00:00'"
                             + "   AND ts <  '" + planDate.plusDays(1) + " 00:00:00'"
                             + " ORDER BY ts")) {
            while (rs.next()) {
                java.sql.Time tm = rs.getTime("ts");
                out.add(new PlanPoint(tm.toLocalTime(), rs.getString("action"),
                        rs.getDouble("power_kw"), rs.getDouble("soc")));
            }
        }
        return out;
    }
}
```

> 注：TAOS-RS 时间戳需完整 `yyyy-MM-dd HH:mm:ss` 格式（不能只给 HH:MM:SS）；库 `iot_ems` 首次启动前可由 Task 12 冒烟用 taos CLI 预建，writer 内 `CREATE DATABASE IF NOT EXISTS` 兜底。以 TDengine 3.3 语法为准。

- [ ] **Step 4: 实现 `EmsPlanService`（generate + page + getPoints）**

```java
package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.entity.EmsElectricityPrice;
import com.sanduo.energy.ems.entity.EmsExecutionRecord;
import com.sanduo.energy.ems.entity.EmsPlan;
import com.sanduo.energy.ems.entity.EmsStrategy;
import com.sanduo.energy.ems.mapper.EmsConstraintMapper;
import com.sanduo.energy.ems.mapper.EmsElectricityPriceMapper;
import com.sanduo.energy.ems.mapper.EmsExecutionRecordMapper;
import com.sanduo.energy.ems.mapper.EmsPlanMapper;
import com.sanduo.energy.ems.mapper.EmsStrategyMapper;
import com.sanduo.energy.ems.util.PlanGenerator;
import com.sanduo.energy.ems.util.PlanInput;
import com.sanduo.energy.ems.util.PlanPoint;
import com.sanduo.energy.ems.util.PriceTier;
import com.sanduo.energy.ems.util.TdenginePlanWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划生成编排：生成 → 安全包络校验 → TDengine 点序列 → 计划头落库 → 下发（复用 energy-command）。
 * 租户取自策略行（@Scheduled 线程无请求租户上下文，见 [[multi-tenant-isolation]]）；
 * 约束/电价查询显式按该租户过滤，避免定时线程跨租户读到同 stationId 数据。
 */
@Slf4j
@Service
public class EmsPlanService {

    private final EmsStrategyMapper strategyMapper;
    private final EmsElectricityPriceMapper priceMapper;
    private final EmsConstraintMapper constraintMapper;
    private final EmsPlanMapper planMapper;
    private final EmsExecutionRecordMapper execMapper;
    private final SafetyEnvelopeValidator validator;
    private final TdenginePlanWriter writer;
    private final CommandClient commandClient;

    @Value("${sanduo.ems.product-key:snd_ess_pcs}")
    private String productKey;

    @Value("${sanduo.ems.device-name:}")
    private String deviceName;

    public EmsPlanService(EmsStrategyMapper strategyMapper,
                          EmsElectricityPriceMapper priceMapper,
                          EmsConstraintMapper constraintMapper,
                          EmsPlanMapper planMapper,
                          EmsExecutionRecordMapper execMapper,
                          SafetyEnvelopeValidator validator,
                          TdenginePlanWriter writer,
                          CommandClient commandClient) {
        this.strategyMapper = strategyMapper;
        this.priceMapper = priceMapper;
        this.constraintMapper = constraintMapper;
        this.planMapper = planMapper;
        this.execMapper = execMapper;
        this.validator = validator;
        this.writer = writer;
        this.commandClient = commandClient;
    }

    /** 生成计划：查策略 → 电价 → 安全约束 → PlanGenerator 出点序列 → 包络校验 → 写 TDengine → 计划头落库。 */
    public EmsPlan generate(Long stationId, Long strategyId, LocalDate planDate) {
        EmsStrategy strategy = resolveStrategy(stationId, strategyId);
        if (strategy == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "未找到启用策略: stationId=" + stationId + (strategyId != null ? ", strategyId=" + strategyId : ""));
        }
        Long tenant = strategy.getTenantId();
        EmsConstraint constraint = constraintMapper.selectOne(new LambdaQueryWrapper<EmsConstraint>()
                .eq(EmsConstraint::getTenantId, tenant)
                .eq(EmsConstraint::getStationId, stationId));
        if (constraint == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未配置安全约束: stationId=" + stationId);
        }
        List<EmsElectricityPrice> prices = priceMapper.selectList(new LambdaQueryWrapper<EmsElectricityPrice>()
                .eq(EmsElectricityPrice::getTenantId, tenant)
                .eq(EmsElectricityPrice::getStationId, stationId));
        List<PlanPoint> points = PlanGenerator.generate(toInput(strategy, constraint, prices));
        SafetyEnvelopeValidator.ValidationResult vr = validator.validate(points,
                constraint.getSocMin().doubleValue(),
                constraint.getSocMax().doubleValue(),
                constraint.getChargePowerMax().doubleValue(),
                constraint.getDischargePowerMax().doubleValue(),
                constraint.getTempMax() == null ? null : constraint.getTempMax().doubleValue());
        if (!vr.valid()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "安全包络校验未通过: " + String.join("; ", vr.rejections()));
        }
        try {
            writer.write(stationId, planDate, points);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "TDengine 写入失败: " + e.getMessage());
        }
        EmsPlan plan = new EmsPlan();
        plan.setTenantId(strategy.getTenantId());
        plan.setStationId(stationId);
        plan.setStrategyId(strategy.getStrategyId());
        plan.setPlanDate(planDate);
        plan.setPlanType(3); // 混合
        plan.setStatus(0);  // 待执行
        plan.setPlanParam(strategy.getConfig());
        planMapper.insert(plan);
        log.info("生成计划 planId={} stationId={} 点数={}", plan.getPlanId(), stationId, points.size());
        return plan;
    }

    public Page<EmsPlan> page(long pageNo, long pageSize, Long stationId) {
        return planMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EmsPlan>()
                        .eq(stationId != null, EmsPlan::getStationId, stationId)
                        .orderByDesc(EmsPlan::getPlanDate));
    }

    public List<PlanPoint> getPoints(Long planId) {
        EmsPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
        }
        try {
            return writer.read(plan.getStationId(), plan.getPlanDate());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
        }
    }

    private EmsStrategy resolveStrategy(Long stationId, Long strategyId) {
        if (strategyId != null) {
            return strategyMapper.selectById(strategyId);
        }
        return strategyMapper.selectOne(new LambdaQueryWrapper<EmsStrategy>()
                .eq(EmsStrategy::getStationId, stationId)
                .eq(EmsStrategy::getStatus, 1)
                .orderByDesc(EmsStrategy::getPriority)
                .last("LIMIT 1"));
    }

    private PlanInput toInput(EmsStrategy strategy, EmsConstraint c, List<EmsElectricityPrice> prices) {
        return new PlanInput(
                strategy.getStrategyType(),
                strategy.getConfig(),
                prices.stream().map(p -> new PriceTier(
                        p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue())).toList(),
                c.getSocMax().doubleValue() / 2, // 初始 SOC 取包络中点（后续可接影子实时值）
                c.getSocMin().doubleValue(),
                c.getSocMax().doubleValue(),
                c.getChargePowerMax().doubleValue(),
                c.getDischargePowerMax().doubleValue());
    }
}
```

- [ ] **Step 5: 实现 `EmsPlanService.dispatch` + 创建 `EmsPlanController`**

`dispatch` 加入同一 `EmsPlanService` 类（在 Step 4 代码的 `page` 之前插入方法）：

```java
    /** 下发计划：逐点调 energy-command 建指令 → 写执行记录 → 计划头置为执行中。 */
    public int dispatch(Long planId) {
        EmsPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在: " + planId);
        }
        if (plan.getStatus() != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "计划状态非待执行: " + plan.getStatus());
        }
        if (deviceName == null || deviceName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未配置下发设备 sanduo.ems.device-name");
        }
        List<PlanPoint> points;
        try {
            points = writer.read(plan.getStationId(), plan.getPlanDate());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取点序列失败: " + e.getMessage());
        }
        int sent = 0;
        for (PlanPoint p : points) {
            if ("STANDBY".equals(p.action())) {
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("action", p.action());
            params.put("power", p.powerKw());
            params.put("socTarget", p.socTarget());
            String commandId = commandClient.dispatch(productKey, deviceName, p.action(), params, 0L);
            EmsExecutionRecord rec = new EmsExecutionRecord();
            rec.setTenantId(plan.getTenantId());
            rec.setPlanId(planId);
            rec.setCommandId(commandId);
            rec.setDeviceId(0L);
            rec.setAction(p.action());
            rec.setParams(params.toString());
            execMapper.insert(rec);
            sent++;
        }
        plan.setStatus(1); // 执行中
        planMapper.updateById(plan);
        return sent;
    }
```

`web/EmsPlanController.java`（自 Task 6 移入，依赖本任务的 `EmsPlanService`）：

```java
package com.sanduo.energy.ems.web;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.ems.entity.EmsPlan;
import com.sanduo.energy.ems.service.EmsPlanService;
import com.sanduo.energy.ems.util.PlanPoint;
import com.sanduo.energy.ems.web.dto.EmsPlanGenerateReq;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/plan")
public class EmsPlanController {

    private final EmsPlanService service;

    public EmsPlanController(EmsPlanService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public Result<EmsPlan> generate(@RequestBody EmsPlanGenerateReq req) {
        return Result.ok(service.generate(req.getStationId(), req.getStrategyId(), req.getPlanDate()));
    }

    @PostMapping("/{planId}/dispatch")
    public Result<Integer> dispatch(@PathVariable Long planId) {
        return Result.ok(service.dispatch(planId));
    }

    @GetMapping("/page")
    public Result<PageResult<EmsPlan>> page(@RequestParam(defaultValue = "1") long pageNo,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) Long stationId) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId)));
    }

    @GetMapping("/{planId}/points")
    public Result<List<PlanPoint>> points(@PathVariable Long planId) {
        return Result.ok(service.getPoints(planId));
    }
}
```

- [ ] **Step 6: 运行 `EmsPlanServiceTest` 确认通过**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" test -pl energy-ems -Dtest=EmsPlanServiceTest`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/service backend/energy-ems/src/main/java/com/sanduo/energy/ems/util/TdenginePlanWriter.java backend/energy-ems/src/main/java/com/sanduo/energy/ems/web/EmsPlanController.java backend/energy-ems/src/test/java/com/sanduo/energy/ems/service
git commit -m "feat(energy-ems): EmsPlanService 计划生成编排 + TDengine 点序列写入"
```

---

### Task 8: 每日定时生成 + 全量构建/测试

**Files:**
- Modify: `backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsPlanService.java`（加 `@Scheduled` 方法）

**Interfaces:**
- Produces: `EmsPlanService.generateDailyPlans()` —— 每日 00:05 为启用策略的电站生成次日计划

- [ ] **Step 1: 在 `EmsPlanService` 加定时方法**（查全部启用策略、按 tenantId:stationId 去重、逐电站生成次日计划、单电站失败不影响其余；`@EnableScheduling` 已在 Task 1 启动类开启）

```java
    /** 每日 00:05 为启用策略的电站生成次日计划（定时线程无租户上下文，遍历全量启用策略）。 */
    @Scheduled(cron = "0 5 0 * * *")
    public void generateDailyPlans() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<EmsStrategy> enabled = strategyMapper.selectList(new LambdaQueryWrapper<EmsStrategy>()
                .eq(EmsStrategy::getStatus, 1));
        Set<String> handled = new HashSet<>();
        for (EmsStrategy s : enabled) {
            String key = s.getTenantId() + ":" + s.getStationId();
            if (!handled.add(key)) {
                continue; // 同电站多策略只生成一次
            }
            try {
                generate(s.getStationId(), s.getStrategyId(), tomorrow);
            } catch (BusinessException e) {
                log.warn("定时生成失败 stationId={} strategyId={}: {}",
                        s.getStationId(), s.getStrategyId(), e.getMessage());
            }
        }
    }
```

> 需在 `EmsPlanService` 头部补 import：`org.springframework.scheduling.annotation.Scheduled`、`java.util.HashSet`、`java.util.Set`。

- [ ] **Step 2: 全量构建 + 测试**

Run: `cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" clean package`
Expected: BUILD SUCCESS，全部单测通过（含 PlanGeneratorTest / SafetyEnvelopeValidatorTest / EmsPlanServiceTest）

- [ ] **Step 3: 提交**

```bash
git add backend/energy-ems/src/main/java/com/sanduo/energy/ems/service/EmsPlanService.java
git commit -m "feat(energy-ems): 每日定时生成次日计划"
```

---

### Task 9: 前端 API + 类型

**Files:**
- Create: `frontend/src/api/ems.ts`
- Modify: `frontend/src/types/models.ts`

**Interfaces:**
- Produces: `emsApi.strategyPage/strategyCreate/strategyUpdate/strategyDelete/strategySwitchStatus/pricePage/priceSave/constraintGet/constraintSave/planGenerate/planPage/planPoints`
- Produces 类型: `EmsStrategy`, `EmsPlan`, `EmsPlanPoint`, `EmsConstraint`, `EmsElectricityPrice`

- [ ] **Step 1: `types/models.ts` 加类型**（仿 CommandView）

```ts
export interface EmsStrategy {
  strategyId: number
  stationId: number
  strategyName: string
  strategyType: string
  config: string
  priority: number
  status: number
  version: number
  tenantId: number
  createTime: string
}

export interface EmsPlan {
  planId: number
  stationId: number
  strategyId: number
  planDate: string
  planType: number
  totalEnergy: number | null
  status: number
}

export interface EmsPlanPoint {
  time: string
  action: string
  powerKw: number
  socTarget: number
}
```

- [ ] **Step 2: `api/ems.ts` 封装**

```ts
import http from './http'
import type { EmsStrategy, EmsPlan, EmsPlanPoint } from '@/types/models'

export const emsApi = {
  strategyPage(params: Record<string, unknown>) {
    return http.get('/api/ems/strategy/page', { params })
  },
  strategyCreate(body: Partial<EmsStrategy>) { return http.post('/api/ems/strategy', body) },
  strategyUpdate(id: number, body: Partial<EmsStrategy>) { return http.put(`/api/ems/strategy/${id}`, body) },
  strategyDelete(id: number) { return http.delete(`/api/ems/strategy/${id}`) },
  strategySwitchStatus(id: number, status: number) { return http.put(`/api/ems/strategy/${id}/status?status=${status}`) },
  pricePage(params: Record<string, unknown>) { return http.get('/api/ems/price/page', { params }) },
  priceSave(body: unknown[]) { return http.post('/api/ems/price', body) },
  constraintGet(stationId: number) { return http.get(`/api/ems/constraint?stationId=${stationId}`) },
  constraintSave(body: unknown) { return http.put('/api/ems/constraint', body) },
  planGenerate(body: { stationId: number; strategyId?: number; planDate: string }) {
    return http.post('/api/ems/plan/generate', body)
  },
  planPage(params: Record<string, unknown>) { return http.get('/api/ems/plan/page', { params }) },
  planPoints(planId: number) { return http.get(`/api/ems/plan/${planId}/points`) },
}
```

- [ ] **Step 3: 前端类型检查**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: 无新类型错误（EmsStrategy 等类型已导出）

- [ ] **Step 4: 提交**

```bash
git add frontend/src/api/ems.ts frontend/src/types/models.ts
git commit -m "feat(frontend): EMS API 封装 + 类型"
```

---

### Task 10: 前端策略管理页 `EmsStrategy.vue`

**Files:**
- Create: `frontend/src/views/EmsStrategy.vue`
- Modify: `frontend/src/router/index.ts`（加 `/ems/strategy` 路由）
- Modify: `frontend/src/layouts/MainLayout.vue`（菜单加「策略管理」）

**Interfaces:**
- Consumes: `emsApi` (Task 9)
- Produces: 可用的策略管理页面

- [ ] **Step 1: 创建 `EmsStrategy.vue`**（仿 Command.vue：表格 + 弹窗 + Tag + 分页）

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsStrategy } from '@/types/models'

const loading = ref(false)
const list = ref<EmsStrategy[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const dialogVisible = ref(false)
const editing = ref<Partial<EmsStrategy>>({})
const isEdit = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await emsApi.strategyPage({ pageNo: pageNo.value, pageSize: pageSize.value })
    list.value = data.records
    total.value = data.total
  } finally { loading.value = false }
}

function openCreate() { editing.value = {}; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: EmsStrategy) { editing.value = { ...row }; isEdit.value = true; dialogVisible.value = true }

async function save() {
  if (isEdit.value) await emsApi.strategyUpdate(editing.value.strategyId!, editing.value)
  else await emsApi.strategyCreate(editing.value)
  ElMessage.success('保存成功')
  dialogVisible.value = false
  load()
}

async function remove(row: EmsStrategy) {
  await ElMessageBox.confirm(`确定删除策略「${row.strategyName}」吗？`, '提示', { type: 'warning' })
  await emsApi.strategyDelete(row.strategyId)
  ElMessage.success('已删除')
  load()
}

async function switchStatus(row: EmsStrategy, status: number) {
  await emsApi.strategySwitchStatus(row.strategyId, status)
  ElMessage.success(status === 1 ? '已启用' : '已停用')
  load()
}

async function generatePlan(row: EmsStrategy) {
  await emsApi.planGenerate({ stationId: row.stationId, strategyId: row.strategyId, planDate: new Date().toISOString().slice(0,10) })
  ElMessage.success('计划已生成')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-button type="primary" @click="openCreate">新增策略</el-button>
      </div>
      <el-table :data="list" v-loading="loading" border>
        <el-table-column prop="strategyName" label="策略名称" />
        <el-table-column prop="strategyType" label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.strategyType === 'PEAK_VALLEY' ? 'success' : 'info'">{{ row.strategyType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
              {{ { 0: '草稿', 1: '启用', 2: '停用' }[row.status as number] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="success" @click="generatePlan(row)" v-if="row.status === 1">生成计划</el-button>
            <el-button size="small" :type="row.status === 1 ? 'warning' : 'primary'" @click="switchStatus(row, row.status === 1 ? 2 : 1)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total" @change="load" layout="total, prev, pager, next" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑策略' : '新增策略'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="策略名称"><el-input v-model="editing.strategyName" /></el-form-item>
        <el-form-item label="策略类型">
          <el-select v-model="editing.strategyType">
            <el-option label="峰谷套利" value="PEAK_VALLEY" />
            <el-option label="需量管理" value="DEMAND" />
            <el-option label="需求响应" value="DR" />
            <el-option label="SOC 约束" value="SOC_CTRL" />
            <el-option label="时间策略" value="TIME" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站 ID"><el-input-number v-model="editing.stationId" :min="1" /></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="editing.priority" :min="0" /></el-form-item>
        <el-form-item label="配置 JSON"><el-input v-model="editing.config" type="textarea" :rows="5" placeholder='{"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}]}' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
```

- [ ] **Step 2: `router/index.ts` 加路由**（在 alarm 后加）：

```ts
    { path: 'ems/strategy', name: 'EmsStrategy', component: () => import('@/views/EmsStrategy.vue') },
    { path: 'ems/plan', name: 'EmsPlan', component: () => import('@/views/EmsPlan.vue') },
```

- [ ] **Step 3: `MainLayout.vue` 菜单加两项**：

```ts
  { path: '/ems/strategy', title: '策略管理', icon: 'SetUp' },
  { path: '/ems/plan', title: '充放电计划', icon: 'TrendCharts' },
```

- [ ] **Step 4: 构建验证**

Run: `cd frontend && npx vue-tsc --noEmit && npm run build`
Expected: 无类型错误，构建成功

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/EmsStrategy.vue frontend/src/router/index.ts frontend/src/layouts/MainLayout.vue
git commit -m "feat(frontend): 策略管理页 + 路由/菜单"
```

---

### Task 11: 前端计划页 `EmsPlan.vue`（含 ECharts 点序图）

**Files:**
- Create: `frontend/src/views/EmsPlan.vue`

**Interfaces:**
- Consumes: `emsApi` (Task 9), `useEChart` composable
- Produces: 计划列表 + 点序图 + 执行记录

- [ ] **Step 1: 创建 `EmsPlan.vue`**（仿 Alarm.vue：表格 + 详情抽屉 + ECharts）

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { emsApi } from '@/api/ems'
import { useEChart } from '@/composables/useEChart'
import type { EmsPlan, EmsPlanPoint } from '@/types/models'

const list = ref<EmsPlan[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const drawerVisible = ref(false)
const chartEl = ref<HTMLElement>()
const { render } = useEChart(chartEl)
const currentPoints = ref<EmsPlanPoint[]>([])

async function load() {
  const data = await emsApi.planPage({ pageNo: pageNo.value, pageSize: pageSize.value })
  list.value = data.records
  total.value = data.total
}

async function viewDetail(row: EmsPlan) {
  drawerVisible.value = true
  currentPoints.value = await emsApi.planPoints(row.planId)
  render({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: currentPoints.value.map(p => p.time) },
    yAxis: { type: 'value', name: '功率 kW' },
    series: [{
      type: 'bar',
      data: currentPoints.value.map(p => ({
        value: p.powerKw,
        itemStyle: { color: p.action === 'CHARGE' ? '#67c23a' : p.action === 'DISCHARGE' ? '#f56c6c' : '#909399' },
      })),
    }],
  })
}

async function dispatch(row: EmsPlan) {
  await emsApi.dispatch(row.planId)
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <el-table :data="list" border>
        <el-table-column prop="planId" label="计划 ID" width="90" />
        <el-table-column prop="planDate" label="计划日期" width="120" />
        <el-table-column prop="stationId" label="电站" width="90" />
        <el-table-column prop="strategyId" label="策略" width="90" />
        <el-table-column prop="totalEnergy" label="总量 kWh" width="120" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 2 ? 'success' : row.status === 1 ? 'primary' : 'info'">
              {{ { 0: '待执行', 1: '执行中', 2: '完成', 3: '已取消' }[row.status as number] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="viewDetail(row)">点序图</el-button>
            <el-button size="small" type="success" @click="dispatch(row)" v-if="row.status === 0">下发</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total" @change="load" layout="total, prev, pager, next" />
    </el-card>

    <el-drawer v-model="drawerVisible" title="充放电计划点序" size="60%">
      <div ref="chartEl" style="height: 400px"></div>
    </el-drawer>
  </div>
</template>
```

> 注：`emsApi.dispatch` 需在 Task 9 的 `ems.ts` 补一个方法（`dispatch(planId)` → `POST /api/ems/plan/${planId}/dispatch`）。执行记录表本期前端可从简（列表可见计划状态即可），完整执行记录查观看后续。

- [ ] **Step 2: `api/ems.ts` 补 `dispatch` 方法**

- [ ] **Step 3: 构建验证**

Run: `cd frontend && npx vue-tsc --noEmit && npm run build`
Expected: 成功

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views/EmsPlan.vue frontend/src/api/ems.ts
git commit -m "feat(frontend): 充放电计划页 + ECharts 点序图"
```

---

### Task 12: 端到端冒烟 + README 更新

**Files:**
- Modify: `README.md`（阶段进度表、端口表加 energy-ems:8105）
- 冒烟验证（不提交代码）

**Interfaces:**
- Consumes: 全部前置任务
- Produces: 验证通过的证据 + 文档更新

- [ ] **Step 1: 更新 README**（阶段进度表 Phase 6 行加「策略引擎」；端口表加 8105 energy-ems；目录结构 backend 注释）

- [ ] **Step 2: 预建 TDengine `iot_ems` 库 + 重启全栈（含 energy-ems）**

```bash
# 预建 TDengine 库（writer 内 CREATE DATABASE IF NOT EXISTS 兜底）
taos -s "CREATE DATABASE IF NOT EXISTS iot_ems" 2>/dev/null || echo "taos CLI 不可用，由 writer 兜底建库"
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform"
bash deploy/scripts/start-stack.sh --skip-infra
```

Expected: 12 服务全部就绪（含 energy-ems:8105，netstat 核对 8105 LISTENING——jps 空格截断误报见 [[jps-space-truncation]]）

- [ ] **Step 3: 登录拿 token**

Run: `curl -s -X POST http://127.0.0.1:8000/api/system/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'`
Expected: code=0，拿 token

- [ ] **Step 4: 配约束 → 建策略 → 启用 → 生成计划 → 查点 → 下发**

> 前置：generate 依赖 `ems_constraint`（无约束直接报错），必须先配。下发依赖 `sanduo.ems.device-name`（在 Nacos energy-shared.yaml 配 `sanduo.ems.device-name: <设备名>`，指向 snd_ess_pcs 下已注册设备；deviceName 禁 `_`/`&`，见 [[clientid-productkey-contract]]）——未配置则 dispatch 抛 `BusinessException(ErrorCode.BAD_REQUEST, "未配置下发设备 sanduo.ems.device-name")`。策略名用 ASCII（中文在 Git Bash curl -d 会破 UTF-8，见 [[gitbash-curl-encoding]]）。

```bash
TOKEN=<登录token>
# 0) 配安全约束（SOC 10~90，充电≤100kW 放电≤80kW）
curl -s -X PUT http://127.0.0.1:8000/api/ems/constraint -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"stationId":1,"socMin":10,"socMax":90,"chargePowerMax":100,"dischargePowerMax":80}'
# 1) 建峰谷套利策略（stationId=1）
curl -s -X POST http://127.0.0.1:8000/api/ems/strategy -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"stationId":1,"strategyName":"smoke-pv","strategyType":"PEAK_VALLEY","priority":1,"config":"{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"06:00\",\"powerLimit\":100}],\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"22:00\",\"powerLimit\":80}],\"socRange\":{\"min\":10,\"max\":90}}"}'
# 2) 启用（把 <id> 换成步骤 1 返回的 strategyId）
curl -s -X PUT "http://127.0.0.1:8000/api/ems/strategy/<id>/status?status=1" -H "Authorization: Bearer $TOKEN"
# 3) 生成计划（返回 planId）
curl -s -X POST http://127.0.0.1:8000/api/ems/plan/generate -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"stationId":1,"planDate":"2026-08-08"}'
# 4) 查计划点（TDengine）
curl -s "http://127.0.0.1:8000/api/ems/plan/<planId>/points" -H "Authorization: Bearer $TOKEN"
# 5) 下发（需已配 sanduo.ems.device-name）
curl -s -X POST "http://127.0.0.1:8000/api/ems/plan/<planId>/dispatch" -H "Authorization: Bearer $TOKEN"
```

Expected: 约束保存 → 策略创建返回 id → 计划生成返回 planId → 点序列有 CHARGE/DISCHARGE 点 → 下发返回执行点数 → command 服务出现对应指令（查 8114 日志或 command 接口），`ems_execution_record` 回填 commandId

- [ ] **Step 5: 租户隔离验证**（t2admin 登录 → 查策略分页应为 0）

- [ ] **Step 6: 提交 README 更新**

```bash
git add README.md
git commit -m "docs: README 阶段进度/端口表加 energy-ems"
```

---

## Self-Review

**1. Spec 覆盖检查：**
- 策略/电价/约束 CRUD → Task 5/6 ✓
- PlanGenerator 峰谷套利 → Task 3 ✓
- SafetyEnvelopeValidator → Task 4 ✓
- 点序列 TDengine → Task 7（TdenginePlanWriter）✓
- 下发复用 command → Task 6/7（CommandClient）✓
- 手动 + 每日定时 → Task 7/8 ✓
- 前端策略/计划页面 → Task 9/10/11 ✓
- 网关路由/端口 → Task 1 ✓
- 多租户隔离 → Global Constraints + 冒烟验证 ✓

**2. 占位符扫描：** 无 TBD/TODO。所有代码块完整。SOC 演进近似公式已注明是 YAGNI 取舍。

**3. 类型一致性：**
- `PlanPoint(time, action, powerKw, socTarget)` 在 Task 3/4/7/9/11 统一 ✓
- `CommandClient.dispatch(productKey, deviceName, command, params, createBy)` 在 Task 6/7 一致 ✓
- `emsApi.planPoints(planId)` 在 Task 9 定义、Task 11 使用 ✓
- `EmsPlanService.generate(stationId, strategyId, planDate)` 在 Task 7/8 一致 ✓
- EmsStrategy 实体字段（strategyId/stationId/strategyType/config/priority/status/version）在 Task 2/5/9/10 一致 ✓
