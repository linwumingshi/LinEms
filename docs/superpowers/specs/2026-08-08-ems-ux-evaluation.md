# EnergyX 策略管理 / 充放电计划 模块人性化优化评估

> Request-4 part 2：基于对 `EmsStrategy.vue` / `EmsPlan.vue` 及后端 energy-ems 的**读码评估**，验证并细化 `2026-08-08-admin-pages-design.md` §7 的 P0–P2 建议。**本期不实现**；确认后作为独立迭代。
>
> 日期：2026-08-08。全部结论带代码引用，可行性判定基于网关路由/后端能力现状。

---

## 1. 现状盘点（读码结论）

### 1.1 策略管理 `frontend/src/views/EmsStrategy.vue`

数据流：分页列表 → 新增/编辑弹窗（名称 / 类型下拉 / **电站 ID 数字输入** / 优先级 / **配置 JSON 裸文本域**）→ 保存 → 行操作（编辑 / 生成计划 / 启停 / 删除）。

| # | 痛点 | 证据 | 影响 |
|---|---|---|---|
| S1 | 配置是**裸 JSON 文本域**，无结构化编辑、无保存前校验 | `EmsStrategy.vue:178-180` `el-input type=textarea`，placeholder 仅给 PEAK_VALLEY 一个示例；后端 `EmsStrategySaveReq.java:22` `config` 仅 `@NotBlank`，不校验 JSON 语法/schema | JSON 语法错保存时才报 400；**结构化错**（缺 start/end、powerLimit 超上限）要等「生成计划」才由 `PlanGenerator.java:66` 抛 `IllegalArgumentException("策略配置解析失败")`——用户在配置页全程无反馈 |
| S2 | **5 种策略类型，后端只实现 PEAK_VALLEY** | `PlanGenerator.java:34` 非 PEAK_VALLEY 直接 `return List.of()`；类型下拉却开放 5 种（`EmsStrategy.vue:164-170`）；「生成计划」对 status=1 的任意类型都可用（`:135`） | 为 DEMAND/DR/SOC_CTRL/TIME 生成 → **0 个点** → 计划页波形空白，无任何提示。用户以为生成了可用计划 |
| S3 | 电站是**裸数字 ID**，无名称/下拉/校验 | `EmsStrategy.vue:172-174` `el-input placeholder="电站 ID"` | 记不住电站、易填错；多电站时无法区分 |
| S4 | 无「复制策略」 | 列表操作仅 编辑/生成计划/启停/删除（`:132-141`） | 同电站多套窗口参数微调需每次新建重填 |
| S5 | 列表无筛选、createTime 裸 ISO | 无关键字/类型/状态筛选；`:129-131` 直接渲染 `row.createTime` | 策略多时不可用 |
| S6 | 生成计划**日期写死今天**、无确认/预览 | `EmsStrategy.vue:85-94` `planDate` 硬编码 `new Date()` 当天，点击即生成，仅一条成功 toast | 无法生成明日/指定日；误触即生成 |

### 1.2 充放电计划 `frontend/src/views/EmsPlan.vue`

数据流：分页列表（默认选中最近一条）→ 选行 → 拉 `planPoints` + 电价档渲染波形 → 行/头部「下发」→ 成功 toast 后重载。

| # | 痛点 | 证据 | 影响 |
|---|---|---|---|
| P1 | 电站/策略都是**裸 ID** | 列表「电站」列显示数字（`:263`）；页头副题 `电站 ${stationId} · 策略 ${strategyId}`（`:207`）；无策略名列 | 无法从列表分辨哪台电站、什么策略 |
| P2 | **计划页无「生成计划」入口** | 只能在策略页行内点，且日期写死 | 生成/查看割裂 |
| P3 | **无下发后执行追踪** | `dispatch()`（`EmsPlan.vue:184-192`）只弹 toast 后 `load()`；后端 `EmsPlanService.java:192` 置 status=1 逐点下发，**无任何轮询/推送**；`STATUS_TEXT` 的「完成(2)/已取消(3)」（`:32`）UI 上永不出现 | 下发后无执行反馈，「执行中」到结束毫无感知 |
| P4 | 无空态 / 未配置电价提示 | `fetchBands` 出错静默 `return []`（`:96-98`）→ 无底纹也无提示；「未配置安全约束」类生成失败只在策略页以错误 toast 出现，计划页无痕迹 | 波形缺底纹但不告知原因，用户误以为无峰谷逻辑 |

### 1.3 后端支持现状（可行性判定）

| 能力 | 现状 | 判定 |
|---|---|---|
| 电站名称化 | `GET /api/station/page` 已由网关暴露（`energy-gateway application.yml:48`）；`Station.java` 有 `stationName/stationCode/address/装机容量` 等 | ✅ **后端就绪**，前端仅缺 `stationApi` |
| 计划页生成/筛选 | `planGenerate({stationId, strategyId?, planDate})` 已支持（`ems.ts:36`）；`EmsPlanService.java:224` `page()` 已接受 `stationId` 过滤 | ✅ 后端就绪 |
| 电价读取 | `pricePage` 已存在（`ems.ts:24`） | ✅ 可支撑「未配置电价提示」 |
| 波形/点序列 | `planPoints` + TDengine writer（`EmsPlanService.java:117-121`） | ✅ 已具备 |
| 执行追踪叠加实际曲线 | 下发命令已入 `EmsExecutionRecord`；**实际功率曲线需从 device/access 侧取**（增量） | ⚠️ 需明确数据源 |
| 结构化编辑 | 纯前端可做表单；但**需后端明确每种类型 config schema**——目前仅 `PlanInput.java:10-12` 注释提到 PEAK_VALLEY 的 `{chargeWindows, dischargeWindows, socRange}`，且 **`socRange` 在 `PlanGenerator` 中实际未用**（功率上限走 constraint 包络，见 `PlanGenerator.java:72-74`） | ⚠️ 最大不确定点，需先定 schema |

---

## 2. 建议清单（按优先级）

| 优先级 | 优化项 | 现状痛点 | 建议 | 可行性 |
|---|---|---|---|---|
| **P0** | 未实现策略类型标注 / 禁用生成 | S2：5 类型开放但后端只跑 PEAK_VALLEY，生成 0 点无提示 | 「生成计划」对非 `PEAK_VALLEY` 禁用 + 标注「类型暂不支持」；保存时对未支持类型明确提示 | 纯前端，立即可做 |
| **P0** | 配置 JSON 结构化编辑 | S1：裸文本域无校验无提示，错误到生成才爆 | 按类型渲染表单（PEAK_VALLEY：充/放窗口表 + 功率上限，窗口校验 start<end、powerLimit≤包络），保留「切换 JSON 模式」兜底；保存前 JSON 语法校验 | 前端为主；需后端给出**每类型 config schema 权威定义**（先解决 socRange 是否保留） |
| P1 | 电站名称化 | S3 / P1 | 新增 `stationApi`；策略/计划表单用电站下拉（label `stationName`，value `stationId`）；列表/副题显示名称 | 后端已就绪 |
| P1 | 计划页内联「生成计划」 | P2 / S6 | 日期选择器 + 电站下拉 + 策略下拉（按站过滤），调 `planGenerate` | 后端已就绪 |
| P1 | 复制策略 | S4 | 列表加「复制」→ 预填新 dialog（名称加「副本」） | 纯前端 |
| P2 | 一键生成一周计划 | 目前只有每日 00:05 定时 | 前端循环 7 天调 `planGenerate`（或后端加批量接口） | 后端已就绪 |
| P2 | 时间格式化 + 电价空态提示 | S5 / P4 | `createTime`/`planDate` 走 `toLocal`；无有效电价档时在波形卡提示「该电站未配置分时电价」 | 前端 |
| P2 | 下发后执行追踪 | P3 | 轮询计划状态（执行中→完成）；TDengine 实际曲线可得时用 ECharts 叠加实际功率 | 需增量（实际功率数据源确认） |

---

## 3. 结论

- **两个 P0 是本轮最值得做的前置**：S2（假可用）比 S1（配置难写）更伤——它让用户对「生成计划」功能失去信任。两者都**不需要后端改动**即可先落（S1 的结构化表单需后端配合定 schema，可拆两期：先做 S2 禁用/标注，再做 S1 表单）。
- 电站名称化（P1）、计划页生成入口（P1）、复制策略（P1）都是**后端已就绪的纯前端活**，投入小收益直接。
- 执行追踪（P2）是唯一需要明确实际功率数据源的功能，建议单独评估 TDengine/access 侧可行性后再排期。
- 所有建议均不触碰后端现有 API 契约；若后续确认实施，按独立迭代（brainstorming → spec → plan）推进，勿并入本期管理后台迭代。
