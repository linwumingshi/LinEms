# P0-1 电价驱动计划生成 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让分时电价真正驱动 PEAK_VALLEY 计划生成——`priceDriven:true` 时按电价档位自动推导谷充峰放窗口（DEEP/VALLEY 充、PEAK/PEEK 放、FLAT/未覆盖待机），并修复生成期电价有效性过滤与无电价报错。

**Architecture:** 后端 `PlanGenerator` 纯函数新增 `priceDriven` 分支（复用 SOC 演进与 5 分钟粒度，功率 = config 字段回退包络）；`EmsPlanService.generate` 电价查询加 `status=1`+有效期过滤、无生效电价抛 BusinessException、`plan_param` 附电价快照。前端 `strategyConfig.validatePeakValleySaveable` 豁免电价驱动无窗口，`StrategyConfigEditor` 新增电价驱动开关与功率输入。手工窗口模式完全保留。

**Tech Stack:** Java 17 / Spring Boot 3 / MyBatis-Plus / JUnit5 + Mockito；Vue 3 + Element Plus + vitest + @vue/test-utils。

## Global Constraints

- Maven 命令必须带仓库路径：`mvn -Dmaven.repo.local="/d/Program Files/maven-repo"`（Git Bash），在 `backend/` 下执行
- 前端命令在 `frontend/` 下执行：`npx vitest run <file>`、`npx vue-tsc --noEmit`
- 电价类型五档：`DEEP`/`PEEK`/`PEAK`/`FLAT`/`VALLEY`；映射 DEEP|VALLEY→CHARGE，PEAK|PEEK→DISCHARGE，FLAT/其他→待机
- 点序列粒度 `SLOT_MIN=5` 分钟，`end` 排他（`t.isBefore(end)`）
- SOC 演进公式沿用现有近似：`soc += power * SLOT_MIN / 60.0 * 0.01`（充）/ 反向（放）
- 配置 `config` 是 MySQL JSON 列，必须真实 JSON 序列化（不能 `Map.toString()`）
- 手工窗口模式（`priceDriven` 缺省/false）行为**必须**保持不变
- commit message 用中文 conventional 风格（如 `feat(ems): ...`）

---

### Task 1: 后端 PlanGenerator 电价驱动分支

**Files:**
- Modify: `backend/energy-ems/src/main/java/com/energyx/ems/util/PlanGenerator.java`
- Modify: `backend/energy-ems/src/test/java/com/energyx/ems/util/PlanGeneratorTest.java`

**Interfaces:**
- Consumes: `PlanInput(strategyType, config, prices, socInit, socMin, socMax, chargePowerMax, dischargePowerMax)`、`PriceTier(start, end, priceType, price)`、`PlanPoint(time, action, powerKw, socTarget)`（均已有，勿改）
- Produces: `PlanGenerator.generate(PlanInput) : List<PlanPoint>`（新增 priceDriven 分支语义）

- [ ] **Step 1: 追加失败测试**

在 `PlanGeneratorTest` 追加以下用例（沿用现有 `PlanInput` 构造方式，imports 已齐）：

```java
@Test
void priceDriven_standardValleyChargePeakDischarge() {
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true,"chargePower":80,"dischargePower":60}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(8, 0), "DEEP", 0.2),
                    new PriceTier(LocalTime.of(8, 0), LocalTime.of(11, 0), "PEAK", 1.2),
                    new PriceTier(LocalTime.of(11, 0), LocalTime.of(14, 0), "FLAT", 0.6),
                    new PriceTier(LocalTime.of(14, 0), LocalTime.of(18, 0), "VALLEY", 0.3),
                    new PriceTier(LocalTime.of(18, 0), LocalTime.of(22, 0), "PEEK", 1.5)),
            50.0, 10.0, 90.0, 100.0, 100.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    assertNotNull(points);
    // FLAT 段（11:00-14:00）无点
    assertTrue(points.stream().noneMatch(p -> !p.time().isBefore(LocalTime.of(11, 0))
            && p.time().isBefore(LocalTime.of(14, 0))));
    // 谷段充电功率 = config.chargePower（config 优先）
    PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
    assertEquals(80.0, charge.powerKw(), 1e-9);
    // 峰段放电功率 = config.dischargePower
    PlanPoint discharge = points.stream().filter(p -> p.action().equals("DISCHARGE")).findFirst().orElseThrow();
    assertEquals(60.0, discharge.powerKw(), 1e-9);
    // 尾点锚定
    assertEquals(LocalTime.of(23, 55), points.get(points.size() - 1).time());
    assertEquals("STANDBY", points.get(points.size() - 1).action());
}

@Test
void priceDriven_powerFallsBackToEnvelopeWhenConfigMissing() {
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(4, 0), "VALLEY", 0.3),
                    new PriceTier(LocalTime.of(12, 0), LocalTime.of(16, 0), "PEAK", 1.2)),
            50.0, 10.0, 90.0, 100.0, 80.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
    assertEquals(100.0, charge.powerKw(), 1e-9); // 回退 chargePowerMax
    PlanPoint discharge = points.stream().filter(p -> p.action().equals("DISCHARGE")).findFirst().orElseThrow();
    assertEquals(80.0, discharge.powerKw(), 1e-9); // 回退 dischargePowerMax
}

@Test
void priceDriven_noPricesThrows() {
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true}
            """,
            List.of(), 50.0, 10.0, 90.0, 100.0, 80.0);
    assertThrows(IllegalArgumentException.class, () -> PlanGenerator.generate(in));
}

@Test
void priceDriven_socReachesMaxThenChargeStops() {
    // 谷段 4h、功率 100：SOC 从 50 起约 200min 到 90 上限，后续不再产 CHARGE 点
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true,"chargePower":100}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(4, 0), "VALLEY", 0.3)),
            50.0, 10.0, 90.0, 100.0, 80.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    assertTrue(points.stream().allMatch(p -> p.socTarget() <= 90.0001));
    List<PlanPoint> charges = points.stream().filter(p -> p.action().equals("CHARGE")).toList();
    assertTrue(charges.stream().allMatch(p -> p.powerKw() == 100.0));
}

@Test
void priceDriven_duplicateStartDedup() {
    // 同 start 双档（batchSave 非幂等残留）：保留首条 VALLEY(0-2)，跳过 DEEP(0-3)
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(2, 0), "VALLEY", 0.3),
                    new PriceTier(LocalTime.of(0, 0), LocalTime.of(3, 0), "DEEP", 0.2)),
            50.0, 10.0, 90.0, 100.0, 80.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    long chargePoints = points.stream().filter(p -> p.action().equals("CHARGE")).count();
    assertTrue(chargePoints <= 24); // 2h/5min = 24 点上限
}

@Test
void priceDriven_falseKeepsWindowBehavior() {
    // priceDriven=false + 手工窗口：走窗口逻辑，电价存在但不影响
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":false,"chargeWindows":[{"start":"02:00","end":"04:00","powerLimit":100}],"dischargeWindows":[{"start":"18:00","end":"20:00","powerLimit":80}]}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(8, 0), "VALLEY", 0.3)),
            50.0, 10.0, 90.0, 100.0, 80.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    PlanPoint charge = points.stream().filter(p -> p.action().equals("CHARGE")).findFirst().orElseThrow();
    assertEquals(LocalTime.of(2, 0), charge.time());
    assertEquals(100.0, charge.powerKw(), 1e-9); // 窗口逻辑：window.powerLimit
}

@Test
void priceDriven_dischargeStopsAtSocMin() {
    // 长峰段放电：SOC 从 50 起到 10 下限后停止出 DISCHARGE 点
    PlanInput in = new PlanInput("PEAK_VALLEY", """
            {"priceDriven":true,"dischargePower":100}
            """,
            List.of(new PriceTier(LocalTime.of(0, 0), LocalTime.of(10, 0), "PEAK", 1.2)),
            50.0, 10.0, 90.0, 100.0, 100.0);
    List<PlanPoint> points = PlanGenerator.generate(in);
    assertTrue(points.stream().allMatch(p -> p.socTarget() >= 9.9999));
    List<PlanPoint> discharges = points.stream().filter(p -> p.action().equals("DISCHARGE")).toList();
    assertTrue(discharges.stream().allMatch(p -> p.powerKw() == 100.0));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（Git Bash，仓库根）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend" && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" -pl energy-ems -am test -Dtest=PlanGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL——现有实现不读 `priceDriven`，`priceDriven=true` 时走手工窗口分支（无窗口 → 仅尾点），断言（功率 80/60、FLAT 无点等）全部失败。

- [ ] **Step 3: 实现电价驱动分支**

重构 `PlanGenerator.generate`（`util/PlanGenerator.java`）：

```java
public static List<PlanPoint> generate(PlanInput in) {
    if (!"PEAK_VALLEY".equals(in.strategyType())) {
        return List.of();
    }
    List<PlanPoint> points = new ArrayList<>();
    try {
        JsonNode cfg = MAPPER.readTree(in.config());
        double soc = in.socInit();
        if (cfg.path("priceDriven").asBoolean(false)) {
            soc = generatePriceDriven(in, cfg, soc, points);
        }
        else {
            soc = generateByWindows(in, cfg, soc, points);
        }
        // 当日尾点锚定待机（保证前端图时间轴完整）
        points.add(new PlanPoint(LocalTime.of(23, 55), "STANDBY", 0, soc));
        // 按时间升序（不依赖 config 窗口书写顺序）
        points.sort(Comparator.comparing(PlanPoint::time));
    }
    catch (Exception e) {
        throw new IllegalArgumentException("策略配置解析失败: " + e.getMessage(), e);
    }
    return points;
}

/** 手工窗口模式（原逻辑原样提取）：逐窗口出点，返回演进后的 SOC。 */
private static double generateByWindows(PlanInput in, JsonNode cfg, double soc, List<PlanPoint> points) {
    for (JsonNode w : cfg.path("chargeWindows")) {
        LocalTime start = LocalTime.parse(w.path("start").asText());
        LocalTime end = LocalTime.parse(w.path("end").asText());
        double power = windowPower(w, in.chargePowerMax());
        for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
            if (soc >= in.socMax())
                break;
            points.add(new PlanPoint(t, "CHARGE", power, soc));
            soc += power * SLOT_MIN / 60.0 * 0.01;
        }
    }
    for (JsonNode w : cfg.path("dischargeWindows")) {
        LocalTime start = LocalTime.parse(w.path("start").asText());
        LocalTime end = LocalTime.parse(w.path("end").asText());
        double power = windowPower(w, in.dischargePowerMax());
        for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(SLOT_MIN)) {
            if (soc <= in.socMin())
                break;
            points.add(new PlanPoint(t, "DISCHARGE", power, soc));
            soc -= power * SLOT_MIN / 60.0 * 0.01;
        }
    }
    return soc;
}

/**
 * 电价驱动模式：按分时电价档位推导充放动作（DEEP/VALLEY→充，PEAK/PEEK→放，其余待机）。
 * 功率 = config.chargePower/dischargePower（>0），否则回退包络上限。返回演进后的 SOC。
 */
private static double generatePriceDriven(PlanInput in, JsonNode cfg, double soc, List<PlanPoint> points) {
    List<PriceTier> tiers = in.prices();
    if (tiers == null || tiers.isEmpty()) {
        throw new IllegalArgumentException("未配置生效的分时电价");
    }
    double chargePower = cfg.path("chargePower").asDouble(0);
    if (chargePower <= 0)
        chargePower = in.chargePowerMax();
    double dischargePower = cfg.path("dischargePower").asDouble(0);
    if (dischargePower <= 0)
        dischargePower = in.dischargePowerMax();
    Set<LocalTime> seenStarts = new HashSet<>();
    for (PriceTier tier : tiers) {
        if (!seenStarts.add(tier.start()))
            continue; // 同 start 去重，保留首条（batchSave 非幂等残留防御）
        String action = switch (tier.priceType()) {
            case "DEEP", "VALLEY" -> "CHARGE";
            case "PEAK", "PEEK" -> "DISCHARGE";
            default -> null; // FLAT/其他 → 待机，不产点
        };
        if (action == null)
            continue;
        double power = "CHARGE".equals(action) ? chargePower : dischargePower;
        for (LocalTime t = tier.start(); t.isBefore(tier.end()); t = t.plusMinutes(SLOT_MIN)) {
            if ("CHARGE".equals(action)) {
                if (soc >= in.socMax())
                    break;
                points.add(new PlanPoint(t, action, power, soc));
                soc += power * SLOT_MIN / 60.0 * 0.01;
            }
            else {
                if (soc <= in.socMin())
                    break;
                points.add(new PlanPoint(t, action, power, soc));
                soc -= power * SLOT_MIN / 60.0 * 0.01;
            }
        }
    }
    return soc;
}
```

新增 import：`java.util.HashSet`、`java.util.Set`。保留 `windowPower` 私有方法（`generateByWindows` 用）。

- [ ] **Step 4: 运行测试确认通过**

Run: 同上 Step 2 命令。
Expected: PASS——新旧 7 个用例全绿（含既有 `peakValley_basicChargeValleyDischargePeak` 回归）。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/util/PlanGenerator.java backend/energy-ems/src/test/java/com/energyx/ems/util/PlanGeneratorTest.java
git commit -m "feat(ems): PlanGenerator 电价驱动分支（priceDriven 谷充峰放，功率 config 回退包络）"
```

---

### Task 2: 后端 EmsPlanService 电价过滤 + 无生效电价拦截 + plan_param 快照

**Files:**
- Modify: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsPlanService.java`
- Modify: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsPlanServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `PlanGenerator.generate`（priceDriven 语义）、`EmsElectricityPrice`（含 `status`/`validFrom`/`validTo`/`startTime`）
- Produces: `EmsPlanService.generate(Long, Long, LocalDate)` 新增行为（过滤/拦截/快照），`EmsPlan.planParam` 在 priceDriven 时含 `priceSnapshot`

- [ ] **Step 1: 追加失败测试**

在 `EmsPlanServiceTest` 追加。**需新增 imports**：`EmsElectricityPrice`（`com.energyx.ems.entity`）、`LambdaQueryWrapper`（`com.baomidou.mybatisplus.core.conditions.query`）、`ArgumentCaptor`（`org.mockito.ArgumentCaptor`）；`BusinessException`/`TdenginePlanWriter`/`EmsStrategy`/`EmsConstraint`/`StringRedisTemplate` 已有。

```java
@Test
void generate_priceDrivenWithoutEffectivePricesThrows() throws Exception {
    EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
    EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
    EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
    EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
    EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

    EmsStrategy s = new EmsStrategy();
    s.setStrategyId(1L);
    s.setStationId(10L);
    s.setTenantId(7L);
    s.setStrategyType("PEAK_VALLEY");
    s.setConfig("{\"priceDriven\":true,\"chargePower\":80}");
    when(stratMapper.selectById(1L)).thenReturn(s);

    EmsConstraint constraint = new EmsConstraint();
    constraint.setSocMin(new BigDecimal("10"));
    constraint.setSocMax(new BigDecimal("90"));
    constraint.setChargePowerMax(new BigDecimal("100"));
    constraint.setDischargePowerMax(new BigDecimal("80"));
    when(constraintMapper.selectOne(any())).thenReturn(constraint);
    when(priceMapper.selectList(any())).thenReturn(List.of()); // 无生效电价

    EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
            new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
            new DistributedLock(mock(StringRedisTemplate.class)));

    BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.generate(10L, 1L, LocalDate.of(2026, 8, 8)));
    assertTrue(ex.getMessage().contains("未配置生效的分时电价"));
}

@Test
void generate_priceDrivenWritesPriceSnapshotParam() throws Exception {
    EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
    EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
    EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
    EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
    EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

    EmsStrategy s = new EmsStrategy();
    s.setStrategyId(1L);
    s.setStationId(10L);
    s.setTenantId(7L);
    s.setStrategyType("PEAK_VALLEY");
    s.setConfig("{\"priceDriven\":true,\"chargePower\":80}");
    when(stratMapper.selectById(1L)).thenReturn(s);

    EmsConstraint constraint = new EmsConstraint();
    constraint.setSocMin(new BigDecimal("10"));
    constraint.setSocMax(new BigDecimal("90"));
    constraint.setChargePowerMax(new BigDecimal("100"));
    constraint.setDischargePowerMax(new BigDecimal("80"));
    when(constraintMapper.selectOne(any())).thenReturn(constraint);

    EmsElectricityPrice p = new EmsElectricityPrice();
    p.setPriceType("VALLEY");
    p.setStartTime(LocalTime.of(0, 0));
    p.setEndTime(LocalTime.of(8, 0));
    p.setPrice(new BigDecimal("0.3"));
    when(priceMapper.selectList(any())).thenReturn(List.of(p));

    EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
            new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
            new DistributedLock(mock(StringRedisTemplate.class)));

    EmsPlan plan = svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

    assertNotNull(plan.getPlanParam());
    assertTrue(plan.getPlanParam().contains("priceSnapshot"));
    assertTrue(plan.getPlanParam().contains("VALLEY"));
}

@Test
void generate_filtersPricesByStatusAndValidity() throws Exception {
    EmsStrategyMapper stratMapper = mock(EmsStrategyMapper.class);
    EmsElectricityPriceMapper priceMapper = mock(EmsElectricityPriceMapper.class);
    EmsConstraintMapper constraintMapper = mock(EmsConstraintMapper.class);
    EmsPlanMapper planMapper = mock(EmsPlanMapper.class);
    EmsExecutionRecordMapper execMapper = mock(EmsExecutionRecordMapper.class);

    EmsStrategy s = new EmsStrategy();
    s.setStrategyId(1L);
    s.setStationId(10L);
    s.setTenantId(7L);
    s.setStrategyType("PEAK_VALLEY");
    s.setConfig("{\"chargeWindows\":[{\"start\":\"02:00\",\"end\":\"04:00\",\"powerLimit\":100}],"
            + "\"dischargeWindows\":[{\"start\":\"18:00\",\"end\":\"20:00\",\"powerLimit\":80}]}"); // 手工模式
    when(stratMapper.selectById(1L)).thenReturn(s);

    EmsConstraint constraint = new EmsConstraint();
    constraint.setSocMin(new BigDecimal("10"));
    constraint.setSocMax(new BigDecimal("90"));
    constraint.setChargePowerMax(new BigDecimal("100"));
    constraint.setDischargePowerMax(new BigDecimal("80"));
    when(constraintMapper.selectOne(any())).thenReturn(constraint);
    when(priceMapper.selectList(any())).thenReturn(List.of()); // 手工模式：电价空不报错

    EmsPlanService svc = new EmsPlanService(stratMapper, priceMapper, constraintMapper, planMapper, execMapper,
            new SafetyEnvelopeValidator(), mock(TdenginePlanWriter.class), mock(CommandClient.class),
            new DistributedLock(mock(StringRedisTemplate.class)));

    svc.generate(10L, 1L, LocalDate.of(2026, 8, 8));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<LambdaQueryWrapper<EmsElectricityPrice>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(priceMapper).selectList(captor.capture());
    String sql = captor.getValue().getSqlSegment();
    assertTrue(sql.contains("status"));
    assertTrue(sql.contains("valid_from"));
    assertTrue(sql.contains("valid_to"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（Git Bash）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend" && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" -pl energy-ems -am test -Dtest=EmsPlanServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL——现有 `generate` 不过滤状态/有效期、无生效电价拦截、plan_param 存原 config。

- [ ] **Step 3: 实现**

改 `EmsPlanService.generate`（`service/EmsPlanService.java:113-138`）：

电价查询加过滤 + 排序（替换 113-115 行）：

```java
List<EmsElectricityPrice> prices = priceMapper
    .selectList(new LambdaQueryWrapper<EmsElectricityPrice>()
        .eq(EmsElectricityPrice::getTenantId, tenant)
        .eq(EmsElectricityPrice::getStationId, stationId)
        .eq(EmsElectricityPrice::getStatus, 1)
        .le(EmsElectricityPrice::getValidFrom, planDate)
        .ge(EmsElectricityPrice::getValidTo, planDate)
        .orderByAsc(EmsElectricityPrice::getStartTime));
```

`toInput` 前加 priceDriven 判断与无生效电价拦截：

```java
boolean priceDriven = isPriceDriven(strategy.getConfig());
if (priceDriven && prices.isEmpty()) {
    throw new BusinessException(ErrorCode.NOT_FOUND,
            "该电站 " + planDate + " 未配置生效的分时电价（status=1 且在有效期内）");
}
```

plan_param 快照（替换 `plan.setPlanParam(strategy.getConfig());`）：

```java
plan.setPlanParam(priceDriven ? buildPriceDrivenParam(strategy.getConfig(), prices) : strategy.getConfig());
```

新增两个私有方法：

```java
private boolean isPriceDriven(String config) {
    try {
        return JSON.readTree(config).path("priceDriven").asBoolean(false);
    }
    catch (Exception e) {
        return false;
    }
}

/** priceDriven 计划：plan_param = { ...config, priceSnapshot:[{priceType,start,end,price}] }；序列化失败回退原 config。 */
private String buildPriceDrivenParam(String config, List<EmsElectricityPrice> prices) {
    try {
        ObjectNode node = (ObjectNode) JSON.readTree(config);
        ArrayNode snapshot = node.putArray("priceSnapshot");
        for (EmsElectricityPrice p : prices) {
            ObjectNode tier = snapshot.addObject();
            tier.put("priceType", p.getPriceType());
            tier.put("start", p.getStartTime().toString());
            tier.put("end", p.getEndTime().toString());
            tier.put("price", p.getPrice().doubleValue());
        }
        return JSON.writeValueAsString(node);
    }
    catch (Exception e) {
        return config;
    }
}
```

新增 import：`com.fasterxml.jackson.databind.node.ObjectNode`、`com.fasterxml.jackson.databind.node.ArrayNode`、`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`（已有）。

- [ ] **Step 4: 运行测试确认通过**

Run: 同上 Step 2 命令。
Expected: PASS——新增 3 用例 + 既有 5 用例全绿（既有 `generate_createsPlanAndWritesPoints` 手工模式 config、`priceMapper.selectList(any())→List.of()` 不受影响）。

- [ ] **Step 5: Commit**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/service/EmsPlanService.java backend/energy-ems/src/test/java/com/energyx/ems/service/EmsPlanServiceTest.java
git commit -m "feat(ems): 生成期电价有效性过滤 + 无生效电价拦截 + plan_param 电价快照"
```

---

### Task 3: 前端 strategyConfig 电价驱动豁免

**Files:**
- Modify: `frontend/src/utils/strategyConfig.ts`
- Modify: `frontend/src/utils/__tests__/strategyConfig.spec.ts`

**Interfaces:**
- Consumes: 现有 `parseJsonConfig`、`validatePeakValleyConfig`
- Produces: `validatePeakValleySaveable(config: string): string[]` 新增 `priceDriven === true` 豁免

- [ ] **Step 1: 追加失败测试**

在 `frontend/src/utils/__tests__/strategyConfig.spec.ts` 的 describe 内追加：

```ts
it('validatePeakValleySaveable：priceDriven=true 无窗口 → 通过', () => {
  expect(validatePeakValleySaveable('{"priceDriven":true,"chargePower":80}')).toEqual([])
  expect(validatePeakValleySaveable('{"priceDriven":true}')).toEqual([])
})

it('validatePeakValleySaveable：priceDriven=false/缺失 无窗口 → 仍拦截', () => {
  expect(validatePeakValleySaveable('{"priceDriven":false,"chargeWindows":[],"dischargeWindows":[]}')).toEqual(['请至少配置一个充电或放电窗口'])
})

it('serializePeakValley：rest 含 priceDriven 键原样保留', () => {
  const s = serializePeakValley({ chargeWindows: [], dischargeWindows: [] }, { priceDriven: true, chargePower: 80 })
  const back = JSON.parse(s)
  expect(back.priceDriven).toBe(true)
  expect(back.chargePower).toBe(80)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run（Git Bash，`frontend/` 下）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vitest run src/utils/__tests__/strategyConfig.spec.ts
```
Expected: FAIL——现有 `validatePeakValleySaveable` 对 `{"priceDriven":true,"chargePower":80}` 报"请至少配置一个充电或放电窗口"。

- [ ] **Step 3: 实现**

改 `validatePeakValleySaveable`（`frontend/src/utils/strategyConfig.ts`）：在"至少一个窗口"判断前加豁免：

```ts
/** 保存闸：结构校验（validatePeakValleyConfig）+ 至少一个窗口。电价驱动（priceDriven=true）豁免——窗口可空，功率缺省回退包络。 */
export function validatePeakValleySaveable(config: string): string[] {
  if (!config.trim()) return ['请至少配置一个充电或放电窗口'] // 空配置 ≠ 非法 JSON，先给「至少一个窗口」
  const issues = validatePeakValleyConfig(config)
  if (issues.length) return issues
  const parsed = parseJsonConfig(config)
  if (!parsed.ok) return [parsed.error] // 理论不可达（上面已过结构校验），防御窄化
  const obj = parsed.value as {
    priceDriven?: boolean
    chargeWindows?: unknown[]
    dischargeWindows?: unknown[]
  }
  if (obj.priceDriven === true) return [] // 电价驱动：窗口可空，功率缺省回退包络
  if ((obj.chargeWindows?.length ?? 0) === 0 && (obj.dischargeWindows?.length ?? 0) === 0) {
    return ['请至少配置一个充电或放电窗口']
  }
  return []
}
```

其余函数（`parsePeakValleyConfig`/`serializePeakValley`/`validatePeakValleyConfig`）**不改**——`priceDriven`/`chargePower`/`dischargePower` 已作为 rest 原样保留（`serializePeakValley` = `{...rest, chargeWindows, dischargeWindows}`）。

- [ ] **Step 4: 运行测试确认通过**

Run: 同上 Step 2 命令。
Expected: PASS——新增 3 用例 + 既有全部用例（`validatePeakValleySaveable('{"chargeWindows":[],"dischargeWindows":[]}')` 仍拦截，因无 priceDriven）。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/strategyConfig.ts frontend/src/utils/__tests__/strategyConfig.spec.ts
git commit -m "feat(frontend): 策略保存校验 priceDriven 豁免（电价驱动无窗口合法）"
```

---

### Task 4: 前端 StrategyConfigEditor 电价驱动开关与功率输入

**Files:**
- Modify: `frontend/src/components/StrategyConfigEditor.vue`
- Modify: `frontend/src/components/__tests__/StrategyConfigEditor.spec.ts`

**Interfaces:**
- Consumes: Task 3 的 `validatePeakValleySaveable` 语义；`parsePeakValleyConfig` 的 `rest`（含 `priceDriven`/`chargePower`/`dischargePower`）
- Produces: 组件 `modelValue` 在开关开启时序列化含 `priceDriven:true` + 可选 `chargePower`/`dischargePower`

- [ ] **Step 1: 追加失败测试**

在 `frontend/src/components/__tests__/StrategyConfigEditor.spec.ts` 追加（复用现有 `mountEditor`）：

```ts
it('峰谷 + priceDriven config → 渲染电价驱动开关与功率输入，无窗口表', () => {
  const config = JSON.stringify({ priceDriven: true, chargePower: 80 })
  const wrapper = mountEditor(config, 'PEAK_VALLEY')
  expect(wrapper.find('.price-drive-bar').exists()).toBe(true)
  expect(wrapper.findAll('.window-row')).toHaveLength(0)
})

it('峰谷 + 手工 config → 渲染窗口表，无功率输入', () => {
  const config = JSON.stringify({
    chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 100 }],
    dischargeWindows: [],
  })
  const wrapper = mountEditor(config, 'PEAK_VALLEY')
  expect(wrapper.findAll('.window-row')).toHaveLength(1)
  expect(wrapper.find('.power-fields').exists()).toBe(false)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run（Git Bash，`frontend/` 下）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vitest run src/components/__tests__/StrategyConfigEditor.spec.ts
```
Expected: FAIL——第一个用例 `.price-drive-bar` 不存在（组件尚未实现开关）。

- [ ] **Step 3: 实现**

改 `frontend/src/components/StrategyConfigEditor.vue`：

**script 新增 state**（`ref` 区域）：

```ts
/** 电价驱动开关与功率（PEAK_VALLEY 结构化模式）；三键从 rest 读写，序列化经 rest 保留 */
const priceDriven = ref(false)
const chargePower = ref<number | undefined>(undefined)
const dischargePower = ref<number | undefined>(undefined)
/** initFromConfig 批量回填三键时临时禁用 watch，防多余 emit */
let initializing = false
```

**`initFromConfig` 结构化分支回填**（在 `form.value = structured.config` 之后加）：

```ts
initializing = true
priceDriven.value = structured.rest.priceDriven === true
chargePower.value = typeof structured.rest.chargePower === 'number' ? (structured.rest.chargePower as number) : undefined
dischargePower.value = typeof structured.rest.dischargePower === 'number' ? (structured.rest.dischargePower as number) : undefined
initializing = false
```

**`switchMode` JSON→form 分支同样回填**（在 `form.value = structured.config` 后加相同三段）。

**新增 watch**（放 `watch(form, ...)` 之后）：

```ts
watch([priceDriven, chargePower, dischargePower], () => {
  if (initializing || !isPeakValley.value) return
  const next: Record<string, unknown> = { ...rest.value, priceDriven: priceDriven.value }
  if (chargePower.value !== undefined) next.chargePower = chargePower.value
  else delete next.chargePower
  if (dischargePower.value !== undefined) next.dischargePower = dischargePower.value
  else delete next.dischargePower
  rest.value = next
  emitConfig()
})
```

**template 结构化表单**（`<template v-if="isPeakValley && mode === 'form'">` 内，把现有窗口表包进 `v-if="!priceDriven"`，顶部加开关与功率输入）：

```html
<div class="price-drive-bar">
  <span class="group-label">电价驱动</span>
  <el-switch v-model="priceDriven" size="small" />
  <span class="drive-hint">开启后按分时电价自动推导谷充峰放窗口</span>
</div>

<div v-if="priceDriven" class="power-fields">
  <div class="power-row">
    <span class="group-label">充电功率</span>
    <el-input-number v-model="chargePower" :min="0.1" :precision="1" :step="1" :placeholder="'留空回退包络上限'" style="width: 140px" />
    <span class="unit">kW</span>
  </div>
  <div class="power-row">
    <span class="group-label">放电功率</span>
    <el-input-number v-model="dischargePower" :min="0.1" :precision="1" :step="1" :placeholder="'留空回退包络上限'" style="width: 140px" />
    <span class="unit">kW</span>
  </div>
  <el-alert v-if="warnings.length" type="warning" :closable="false" class="warn-alert" :title="warnings.join('；')" />
</div>

<template v-else>
  <!-- 现有 windowGroups 窗口表原样保留（group-head/group-empty/window-row） -->
  <div v-for="group in windowGroups" :key="group.key" class="window-group">
    <div class="group-head">
      <span class="group-label">{{ group.label }}</span>
      <el-button link type="primary" size="small" @click="addWindow(group.key)">{{ group.addLabel }}</el-button>
    </div>
    <div v-if="form[group.key].length === 0" class="group-empty">暂无窗口</div>
    <div v-for="(w, i) in form[group.key]" :key="i" class="window-row">
      <el-time-picker v-model="w.start" format="HH:mm" value-format="HH:mm" placeholder="开始" :clearable="false" style="width: 100px" />
      <span class="sep">至</span>
      <el-time-picker v-model="w.end" format="HH:mm" value-format="HH:mm" placeholder="结束" :clearable="false" style="width: 100px" />
      <el-input-number v-model="w.powerLimit" :min="0.1" :precision="1" :step="1" style="width: 120px" />
      <span class="unit">kW</span>
      <el-button link type="danger" size="small" @click="removeWindow(group.key, i)">删除</el-button>
    </div>
  </div>
  <el-alert v-if="issues.length" type="error" :closable="false" class="block-alert" :title="issues.join('；')" />
  <el-alert v-if="warnings.length" type="warning" :closable="false" class="warn-alert" :title="warnings.join('；')" />
</template>
```

**warnings computed 适配**：电价驱动模式下窗口表隐藏，`warnings` 基于 `form.value` 的窗口计算 → 无窗口即空数组，天然不显示。无需改动逻辑。

**style 追加**：

```css
.price-drive-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.drive-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.power-fields {
  margin-bottom: 8px;
}
.power-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vitest run src/components/__tests__/StrategyConfigEditor.spec.ts
```
Expected: PASS——新增 2 用例 + 既有 3 用例全绿。随后跑全量类型检查：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vue-tsc --noEmit
```
Expected: 0 错。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/StrategyConfigEditor.vue frontend/src/components/__tests__/StrategyConfigEditor.spec.ts
git commit -m "feat(frontend): 策略配置电价驱动开关与功率输入（结构化表单）"
```

---

### Task 5: 全量 gate 与冒烟

**Files:**
- 无代码改动（验证 + 报告）

**Interfaces:**
- Consumes: Task 1-4 全部实现

- [ ] **Step 1: 后端全量测试**

Run（Git Bash，`backend/`）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/backend" && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" -pl energy-ems -am test
```
Expected: energy-ems 模块全部测试通过（含既有 PlanGeneratorTest/EmsPlanServiceTest/SafetyEnvelopeValidatorTest + 新增用例）。

- [ ] **Step 2: 前端全量测试 + 类型检查**

Run（Git Bash，`frontend/`）：
```bash
cd "/d/ProgramData/Codex-Data/Energy Storage IoT Platform/frontend" && npx vitest run && npx vue-tsc --noEmit
```
Expected: vitest 全绿 + vue-tsc 0 错。

- [ ] **Step 3: 冒烟验证（需服务栈已启动）**

手动冒烟（按 spec §6.3），依赖服务栈（Nacos → MySQL → Kafka → TDengine → 各业务服务 → 网关）已运行。用 curl 经网关：

```bash
# 1. 配 priceDriven 策略（登录拿 token 后）
curl -s -X POST http://127.0.0.1:8000/api/ems/strategy -H "Content-Type: application/json" -H "x-user-token: <TOKEN>" \
  -d '{"stationId":<STATION>,"strategyName":"电价驱动测试","strategyType":"PEAK_VALLEY","config":"{\"priceDriven\":true,\"chargePower\":80,\"dischargePower\":60}"}'
# 2. 配电价（DEEP 00-08 / PEAK 08-11 / FLAT 11-14 / VALLEY 14-18 / PEEK 18-22，status=1，有效期覆盖今日）
# 3. 生成计划
curl -s -X POST http://127.0.0.1:8000/api/ems/plan/generate -H "Content-Type: application/json" -H "x-user-token: <TOKEN>" \
  -d '{"stationId":<STATION>,"planDate":"<TODAY>"}'
# 4. 拉点序列 → 前端波形/接口核对：谷段 CHARGE、峰段 DISCHARGE、平段无点
curl -s http://127.0.0.1:8000/api/ems/plan/<PLAN_ID>/points -H "x-user-token: <TOKEN>"
# 5. 反例：不配电价 → 生成应返回「未配置生效的分时电价」
```

验收断言（spec §6.3 五项）：
1. 电价驱动计划：谷段充电、峰段放电、平段待机
2. 无电价 → toast「未配置生效的分时电价」
3. 电价 `status=0` 或 `valid_to < planDate` → 同 2 报错
4. `chargePower=80` → 点功率 80；删字段 → 点功率=包络上限
5. 旧手工窗口策略生成 → 波形与改动前一致

- [ ] **Step 4: 完成报告**

向用户报告：改动文件清单、测试结果、冒烟结论、遗留项（电价 batchSave 幂等 → P0-5，非本迭代）。
