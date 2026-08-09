# 子项目B 设备数据中心 — 浏览器冒烟报告

- 日期：2026-08-09
- 范围：设备管理 /device 页 —— 登录、按名检索 sim-dev-000001、详情抽屉加宽 820px 与双 tab、运行状态（影子最新值卡片 + 最后上报时间）、历史查询（默认近 24h 折线图 + 分页表格）、无数据时间窗空态
- 环境：vite dev :25173 + 网关 :8000（本机原生运行）；TDengine :6041 已造数（`st_prop_snd_ess_pcs`，Task 1）；Edge 无头 + playwright-core（frontend/node_modules）
- 结果：**11 PASS / 0 FAIL — ALL_PASS**

## 逐场景

| # | 场景 | 结果 |
|---|------|------|
| 1 | 登录成功进入主界面 | PASS |
| 2 | 侧栏「设备管理」进入 /device 设备管理页 | PASS |
| 3 | 按名称模糊检索 sim-dev-000001 命中 | PASS |
| 4 | 详情抽屉加宽至 820px | PASS |
| 5 | 抽屉出现「运行状态」tab | PASS |
| 6 | 运行状态卡片区含 soc 且值非 —（影子 reported） | PASS |
| 7 | 最后上报时间非空（影子 lastReportedTime） | PASS |
| 8 | 历史查询默认近 24h → 折线图 canvas 渲染 | PASS |
| 9 | 历史数据表格 ≥1 行（TDengine 数据） | PASS |
| 10 | 翻到第 2 页仍有数据（总行数 > 20） | PASS |
| 11 | 切到未来时间窗查询 → 空态提示 | PASS |

## 说明

- **首轮冒烟 1 次瞬态失败（非产品缺陷）**：首轮运行在步骤 6 等待 `.rt-card` 超时（10s 窗口不足）。根因：Edge 无头首次冷启动 + 网关链路（vite → 网关 :8000 → energy-shadow / energy-product → TDengine/Nacos）首次请求延迟叠加，超出 10s 等待；随后重跑 11/11 全绿。探针脚本确认影子 `GET /api/shadow/8000000000000000001` 与物模型 `GET /api/product/thing-model/by-key?productKey=snd_ess_pcs` 均返回 200，卡片在数据加载完成后正常渲染，功能本身无缺陷。
- **验证命令**：vitest 17 文件 / 101 用例全绿；`vue-tsc --noEmit` EXIT 0；后端 `mvn -pl energy-tsdb,energy-product -am test` BUILD SUCCESS（退出码 0，`-q` 抑制 reactor 汇总行）。
- **冒烟数据**：Task 1 种子设备 sim-dev-000001（在线、影子 reported 非空，soc=86 等）；历史数据来自 `st_prop_snd_ess_pcs`（约 59 行，runMode 的 NULL 残留行无害）。
- **脚本解析与重跑**：playwright-core 位于 `frontend/node_modules`，ESM 解析自脚本所在路径（`test/smoke/`）逐级向上查找，`frontend/node_modules` 不在解析链上；须在 repo 根建 `node_modules` 目录联接（junction）指向 `frontend/node_modules`，`import 'playwright-core'` 方可解析。该联接为本机未跟踪产物（不入库、勿 `git add`，跑完即删）。重跑步骤：① 建 junction（Git Bash，repo 根）：`cmd //c mklink //J node_modules "<repo-root>\frontend\node_modules"`；② 跑冒烟：`node test/smoke/smoke-device-center.mjs`；③ 移除（PowerShell，仅删联接不触目标）：`(Get-Item -LiteralPath "<repo-root>\node_modules" -Force).Delete()`。
- 产品代码零改动。

## 结论

设备数据中心（子项目B）浏览器端全场景通过，与影子/TDengine 全链路一致。
