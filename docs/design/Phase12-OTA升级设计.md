# EnergyX 储能管理平台 — Phase 12 OTA 固件升级设计

> 阶段目标：参考阿里云 IoT / 华为云 IoTDA / 涂鸦等主流物联网平台的 OTA 升级设计，
> 结合本项目既有 MQTT 接入、消息总线、设备/命令/影子体系，设计并落地「OTA 固件升级」能力。
> 本阶段先产出详细设计文档（数据模型 / 设备协议 / 时序 / 灰度 / 安全），评审通过后进入编码。

| 项目 | 内容 |
| --- | --- |
| 项目名称 | EnergyX 储能管理平台 |
| 阶段 | Phase 12：OTA 固件升级 |
| 版本 | v0.1（设计稿） |
| 日期 | 2026-08-15 |
| 设计定位 | 在既有 Kafka 消息总线 + MQTT 接入之上构建固件升级能力，与设备中心、命令中心、告警中心解耦协作 |

---

## 1. 背景与目标

### 1.1 业务背景

储能设备（EMS 控制器、PCS 逆变器、BMS、电表网关）分布部署在电站现场，固件迭代是常态：

- 安全补丁（BMS 保护逻辑、PCS 功率控制 bug）；
- 功能迭代（新的策略算法、协议适配）；
- 配置变更（计量参数、通信参数）。

人工现场刷写成本高、周期长、易出错（偏远电站、高空柜体），必须支持**远程固件升级（OTA）**。

平台已有：自研 MQTT Broker（设备接入）、Kafka 消息总线（上行 mqtt.uplink / 下行 mqtt.down.{nodeId}）、
设备中心（iot_device 含 firmware_version）、命令中心（指令下发）、影子（设备状态缓存）、告警中心。
OTA 应**复用这些基础设施**，不重复造传输通道，仅在协议层新增 OTA 专属 topic 与升级作业管理。

### 1.2 目标

1. **升级包管理**：上传固件文件（本地存储，预留对象存储抽象）、版本号、源版本约束、MD5/SHA256 校验、签名验签；
2. **批次升级任务**：按产品 + 设备范围（指定设备 / 分组 / 灰度比例）创建任务，支持重试策略、超时控制、取消/重试；
3. **设备协议**：版本上报、升级通知下发、进度上报、结果上报、主动拉取（离线补推），
   支持 HTTPS 分片下载与断点续传、**差分升级（bsdiff，无差分自动退化全量）**；
4. **灰度发布**：1% → 10% → 50% → 100% 渐进式推送，失败率激增自动暂停（参考主流平台最佳实践）；
5. **状态跟踪**：待升级 / 下载中 / 升级中 / 成功 / 失败 / 超时 / 已取消，升级结果与版本分布统计；
6. **安全**：固件完整性校验（SHA256）、签名验签（预留 RSA）、仅 HTTPS 下载，杜绝中间人篡改；
7. **集成**：升级成功后回写 iot_device.firmware_version，失败触发告警 + 通知。

---

## 2. 参考方案对比

| 维度 | 阿里云 IoT | 华为云 IoTDA | 本方案取舍 |
| --- | --- | --- | --- |
| 版本上报 | `/ota/device/inform/{pk}/{dn}` 启动时上报一次 | 升级协商前下发命令查版本 | 设备启动 + 上线后上报一次（`ota/inform`） |
| 升级通知 | `/ota/device/upgrade/{pk}/{dn}` 推 URL | 下发下载 URL + 分段参数 | 复用下行通道推信封（含 URL/版本/大小/签名） |
| 进度上报 | `/ota/device/progress/{pk}/{dn}`（间隔 ≥3s） | 状态订阅 + 分片下载进度 | `ota/progress`（0-100 + 阶段） |
| 成功判定 | 设备上报新版本号 == 目标版本（唯一判据） | 版本比对 | 同阿里云：**以新版本上报为唯一成功判据** |
| 离线补推 | 上线检测 → 校验需升级 → 补推 | 24h 升级协商窗口 | 监听 lifecycle ONLINE → 校验任务 → 补推 |
| 断点续传 | HTTPS Range / MQTT 流下载 | 分片下载（32-500B）+ 断点续传 | HTTPS Range 分片（1MB/片），SHA256 块校验 |
| 差分升级 | 支持（baseVersion 生成差分包） | 支持（NB 场景标配） | **支持**：bsdiff/bspatch，按设备版本匹配差分，无差分自动退化全量 |
| 重试策略 | 无显式 | 2 次 / 5min 间隔（建议值） | 默认 2 次 / 5min，任务级可配 |
| 超时 | 任务级超时 | 下载 60min / 升级 30min | 下载 60min / 升级 30min（任务可配） |
| 灰度 | 分批 + 设备标签 | 按比例/分组 | 按比例（1-10-50-100）+ 失败自动暂停 |
| 安全 | MD5 校验 + OSS 签名 URL | 版本校验码 | SHA256 + RSA 签名验签（预留）+ HTTPS |
| 任务清理 | 保留历史 | 批次任务 30 天后自动清理 | 任务保留 90 天（审计），明细同表 |

---

## 3. 总体架构

### 3.1 模块划分

新增微服务 **energy-ota（OTA 升级中心）**，端口 **8118**（接续现有序列），xxl-job 执行器端口错开（9997）。

```
┌─────────────┐   HTTP    ┌──────────────────────────────────────────────┐
│ 管理前端     │ ────────▶ │  energy-ota（OTA 升级中心 :8118）             │
│ (升级包/任务) │           │  ├─ PackageController  升级包 CRUD + 上传     │
└─────────────┘           │  ├─ TaskController     批次任务/明细/统计     │
                          │  ├─ OtaPackageService  文件存储+校验+签名     │
                          │  ├─ OtaTaskService     任务调度/灰度/重试     │
                          │  ├─ OtaDeviceFlow      Kafka 上下行编排       │
                          │  └─ OtaScheduler       超时扫描/灰度推进      │
                          └──────────┬───────────────────────────────────┘
                                     │ Kafka
        ┌───────────────┬────────────┼──────────────┬────────────────────┐
        ▼               ▼            ▼              ▼                    ▼
 mqtt.uplink      ota.uplink   ota.down.{nodeId}  iot-device-lifecycle  iot-alarm
 (access 消费)   (ota 消费)    (access 消费桥接)  (ota 订阅上线补推)   (失败告警)
        │               │            │
        ▼               │            ▼
  access 标准化        │      access → broker → 设备订阅 topic
  (物模型链路)          │      {pk}/{dn}/ota/down
                       ▼
                ota 报文识别转发
```

### 3.2 消息通道（Kafka Topic）

| Topic | 方向 | 生产 / 消费 | 说明 |
| --- | --- | --- | --- |
| `ota.uplink` | 上行 | access 生产 / ota 消费（唯一消费组） | 设备 OTA 报文（inform/progress/result/pull），key=deviceId |
| `ota.down.{nodeId}` | 下行 | ota 生产 / access 消费 | 升级通知信封，按 `mqtt:conn:{deviceKey}` owner 定向（与 mqtt.down 同机制） |
| `iot-device-lifecycle` | 事件 | device/access 生产 / ota 订阅 | 设备上线事件，触发离线任务补推 |
| `iot-alarm` | 事件 | ota 生产 | 升级失败/异常推送告警中心 |

> **路由规则**：access 消费 `mqtt.uplink` 时，若报文原始 topic 以 `ota/` 前缀开头，则**原样透传**到
> `ota.uplink`（不进入物模型标准化链路，避免污染物模型）；其余报文走既有标准化流程。
> 下行：ota 服务 → `ota.down.{nodeId}` → access 桥接为 `{pk}/{dn}/ota/down` 信封投递 Broker
> （复用现有 `mqtt.router` PUBLISH 信封机制，与命令下发同构）。

### 3.3 设备侧 Topic 约定

| Topic（设备视角） | 方向 | 用途 |
| --- | --- | --- |
| `ota/inform` | 上行 publish | 上报当前固件版本 `{"version":"1.0.0","module":"main"}` |
| `ota/progress` | 上行 publish | 上报进度 `{"taskId":"...","progress":45,"state":"DOWNLOADING"}` |
| `ota/result` | 上行 publish | 上报结果 `{"taskId":"...","success":true,"version":"2.0.0","code":0,"msg":""}` |
| `ota/pull` | 上行 publish | 主动拉取升级信息（离线错过通知后）`{"version":"1.0.0"}` |
| `{pk}/{dn}/ota/down` | 下行 subscribe | 接收升级通知信封（URL/版本/大小/签名/分片参数） |

> 设备实际 publish 的完整 topic 由产品约定：`{pk}/{dn}/ota/up` 或裸 `ota/xxx`（Broker 统一前缀）。
> 为避免与物模型 topic（`{pk}/{dn}/up/property` 等）混淆，OTA 使用独立 `ota/` 命名空间。

---

## 4. 数据模型（DDL，库 es_ota）

### 4.1 ota_package 升级包

```sql
CREATE TABLE ota_package (
  package_id    BIGINT       NOT NULL COMMENT '升级包 ID（雪花）',
  tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
  product_key   VARCHAR(64)  NOT NULL COMMENT '产品标识',
  version       VARCHAR(64)  NOT NULL COMMENT '固件版本号（目标版本）',
  module        VARCHAR(32)  NOT NULL DEFAULT 'main' COMMENT '固件模块（预留多模块）',
  package_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '1全量包 2差分包',
  base_version  VARCHAR(64)  NULL     COMMENT '差分源版本（package_type=2 必填，全量包为 NULL）',
  file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_path     VARCHAR(255) NOT NULL COMMENT '存储相对路径（本地目录/对象存储 key）',
  file_size     BIGINT       NOT NULL COMMENT '文件大小（字节）',
  md5           CHAR(32)     NOT NULL COMMENT '文件 MD5（传输校验）',
  sha256        CHAR(64)     NOT NULL COMMENT '文件 SHA256（完整性校验）',
  signature     VARCHAR(512) NULL     COMMENT 'RSA 签名（base64，预留验签）',
  source_versions VARCHAR(255) NULL   COMMENT '可升级源版本列表，逗号分隔；NULL=任意源版本',
  description   VARCHAR(512) NULL     COMMENT '升级说明/变更日志',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2已停用',
  create_by     BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (package_id),
  -- base_version NULL（全量包）在 MySQL 唯一索引中允许多值，差分包按 (version, base_version) 唯一
  UNIQUE KEY uk_pkg_version_base (tenant_id, product_key, version, module, base_version),
  KEY idx_pkg_product (product_key, status),
  KEY idx_pkg_base (product_key, version, base_version)
) COMMENT='OTA 升级包（全量包 + 差分包同表，package_type 区分）';
```

> **差分设计**：
> - **同表存储**：差分包与全量包共用 ota_package，`package_type=2` 的行记录「目标 version + 源 base_version」的差分文件；
>   同一目标版本可存在 1 个全量包 + N 个差分包（对应不同源版本），下发时按设备当前版本精确匹配；
> - **差分生成**：管理端上传全量包后可选择「生成差分包」（指定源版本，平台保留各版本全量包后调 bsdiff 自动生成）；
>   也可由用户直接上传厂商预生成的差分包（省平台计算）；
> - **算法选型**：差分算法推荐 **bsdiff/bspatch**（bzip2 压缩二进制差异，业界事实标准，设备端 C 实现成熟）；
>   服务端生成可用 Java 封装（bsdiff 命令行进程调用或纯 Java xdelta4j 备选）；设备端嵌入 bspatch.c（约 10KB 内存流式合并）；
> - **差分文件本身同样做 MD5/SHA256/签名**，防差分包被篡改。

### 4.2 ota_task 批次升级任务

```sql
CREATE TABLE ota_task (
  task_id       BIGINT       NOT NULL COMMENT '任务 ID（雪花）',
  tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
  package_id    BIGINT       NOT NULL COMMENT '升级包 ID',
  task_name     VARCHAR(128) NOT NULL COMMENT '任务名称',
  task_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '1全部设备 2指定设备 3灰度比例',
  download_policy TINYINT    NOT NULL DEFAULT 1 COMMENT '1差分优先(DIFF_FIRST) 2仅全量(FULL_ONLY)',
  gray_ratio    TINYINT      NULL     COMMENT '灰度比例 1-100（task_type=3）',
  device_count  INT          NOT NULL DEFAULT 0 COMMENT '目标设备数（创建时快照）',
  success_count INT          NOT NULL DEFAULT 0 COMMENT '成功数（冗余统计）',
  fail_count    INT          NOT NULL DEFAULT 0 COMMENT '失败数',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待开始 1执行中 2已完成 3已暂停 4已取消',
  retry_times   TINYINT      NOT NULL DEFAULT 2 COMMENT '失败重试次数',
  retry_interval_min INT     NOT NULL DEFAULT 5 COMMENT '重试间隔（分钟）',
  download_timeout_min INT   NOT NULL DEFAULT 60 COMMENT '下载超时（分钟）',
  upgrade_timeout_min INT    NOT NULL DEFAULT 30 COMMENT '升级超时（分钟）',
  auto_pause_on_fail TINYINT NOT NULL DEFAULT 1 COMMENT '失败率激增自动暂停（1开 0关）',
  schedule_time DATETIME(3)  NULL     COMMENT '计划开始时间（NULL=立即）',
  create_by     BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (task_id),
  KEY idx_task_pkg (package_id),
  KEY idx_task_status (tenant_id, status)
) COMMENT='OTA 批次升级任务';
```

### 4.3 ota_task_device 设备升级明细

```sql
CREATE TABLE ota_task_device (
  task_id       BIGINT       NOT NULL COMMENT '任务 ID',
  device_id     BIGINT       NOT NULL COMMENT '设备 ID',
  tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
  state         TINYINT      NOT NULL DEFAULT 0 COMMENT '0待升级 1下载中 2升级中 3成功 4失败 5超时 6已取消',
  progress      INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
  version_before VARCHAR(64) NULL     COMMENT '升级前版本',
  version_after  VARCHAR(64) NULL     COMMENT '升级后版本（成功回写）',
  fail_code     VARCHAR(32)  NULL     COMMENT '失败码（DOWNLOAD_FAIL/VERIFY_FAIL/UPGRADE_FAIL/TIMEOUT...）',
  fail_msg      VARCHAR(512) NULL     COMMENT '失败描述',
  retry_count   TINYINT      NOT NULL DEFAULT 0 COMMENT '已重试次数',
  retry_at      DATETIME(3)  NULL     COMMENT '下次重试时间',
  start_time    DATETIME(3)  NULL     COMMENT '开始时间',
  finish_time   DATETIME(3)  NULL     COMMENT '结束时间',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (task_id, device_id),
  KEY idx_td_state (state, retry_at),
  KEY idx_td_device (device_id)
) COMMENT='OTA 任务-设备明细（流水表，不做逻辑删除）';
```

> **表设计要点**：
> - ota_package / ota_task 继承 BaseEntity 规范（tenant_id/create_time/update_time/deleted，deleted 由 @TableLogic 处理）；
> - ota_task_device 为流水表（无 deleted 列，物理保留 90 天后清理），不继承基类，仅 @TableName/@TableId 复合主键；
> - 新增模块需在 `MybatisPlusConfig.IGNORE_TABLES` 无需处理（三表均含 tenant_id 列）。

---

## 5. 设备侧升级流程

### 5.1 主流程时序（在线设备）

```
设备                      Broker/access              energy-ota                管理端
 │  上线 + 上报版本             │                          │                       │
 │──ota/inform {version:1.0.0}─▶│──ota.uplink────────────▶│ 记录设备版本缓存      │
 │                              │                          │                       │
 │                              │                          │ 创建任务(灰度1%)       │
 │                              │                          │◀──────────────────────│
 │                              │                          │ 下发通知(在线直推)      │
 │◀──{pk}/{dn}/ota/down─────────│◀──ota.down.{nodeId}──────│                       │
 │  {url,version:2.0.0,size,    │                          │                       │
 │   sha256,segment}            │                          │                       │
 │  HTTPS Range 分片下载         │                          │                       │
 │──ota/progress {45}──────────▶│──ota.uplink────────────▶│ 更新进度/状态=下载中   │
 │──ota/progress {100}─────────▶│                          │                       │
 │  本地升级(AB分区/校验签名)    │                          │                       │
 │  重启 上线 上报新版本          │                          │                       │
 │──ota/inform {version:2.0.0}─▶│──ota.uplink────────────▶│ 比对==目标 → 成功     │
 │                              │                          │ 回写 device.firmware   │
 │                              │                          │ 推进灰度/任务状态       │
```

### 5.2 离线补推

设备离线时任务创建 → 设备无感。监听 `iot-device-lifecycle`（ONLINE）事件：

1. 收到上线事件 → 查询该设备**待升级/下载中**的任务明细（state ∈ 0/1）；
2. 有任务 → 校验设备当前上报版本（若已 == 目标版本则直接置成功，避免重复下发）；
3. 下发升级通知信封（同在线路径）。

### 5.3 主动拉取

设备错过通知（异常重启、topic 订阅时序）时：

- 设备 publish `ota/pull {version:1.0.0}` → ota 服务查询该设备是否有 PENDING 任务 → 有则立即下发通知，无则返回 `{code:204}`（无任务）。

### 5.4 分片下载与断点续传

- 服务端下发信封携带 `segmentSize`（默认 1MB）与 `url`（支持 HTTP Range）；
- 设备按 Range 请求分片，每片完成后 SHA256 校验（块级校验，损坏仅重传该片）；
- 中断后按已落盘 offset 发起续传（参考华为云分片 + 断点续传思想，片大小可配）；
- 全量文件下载完成后二次整体 SHA256 校验 + RSA 签名验签（预留）后才允许安装。

### 5.5 差分升级流程

```
设备上报当前版本 v1.0.0 ──▶ 任务目标版本 v2.0.0
                              │
              ┌───────────────┼──────────────────────┐
              ▼（存在差分包）    ▼（无差分包/匹配失败）    ▼（任务策略 FULL_ONLY）
     下发差分包               下发全量包              下发全量包
     (v1.0.0→v2.0.0.diff)    (v2.0.0.bin)          (v2.0.0.bin)
              │                    │                    │
              ▼                    ▼                    ▼
  设备下载 diff → bspatch 合并      设备下载全量            设备下载全量
  → 生成完整固件 → SHA256 校验      → SHA256 校验          → SHA256 校验
              └────────────────────┴────────────────────┘
                              ▼
                  升级安装 → 重启 → 上报 v2.0.0 → 判定成功
```

**要点**：

1. **包选择策略**（任务级配置 `downloadPolicy`）：
   - `DIFF_FIRST`（默认）：设备上报当前版本后，优先查 `(target_version, device_version)` 差分包；
     命中 → 下发差分；未命中 → 退化为全量包（**差分缺失永不阻断升级**）；
   - `FULL_ONLY`：一律下发全量包（兼容不支持 bspatch 的旧设备）；
2. **匹配粒度**：按 `product_key + version + base_version + module` 精确匹配设备当前版本；
   多跳升级（v1.0.0 → v1.5.0 → v2.0.0 逐跳差分）暂不支持，跨多版本直接下发全量；
3. **设备端合并**：下载差分包 → 用预置 bspatch 与本地当前固件合并生成目标固件（流式合并，
   需设备预留约 1.2× 固件大小临时区）→ 合并产物 SHA256 必须等于全量包 sha256（信封携带
   `targetSha256` 供比对）→ 校验通过才允许安装；
4. **差分包同样分片/断点续传**（复用 5.4），只是合并后多一步目标固件校验；
5. **上报内容**：progress 阶段状态沿用 DOWNLOADING/UPGRADING；result 增加
   `{"packageType":"DIFF","merged":true}` 便于平台统计差分成功率。

### 5.6 升级成功判据（关键）

**以设备上报的新版本号 == 目标版本为唯一成功判据**（同阿里云）。即使进度上报 100%，
未上报新版本且超过升级超时 → 判定失败（防设备假报进度）。

---

## 6. 批次任务与灰度发布

### 6.1 任务创建

| 参数 | 说明 |
| --- | --- |
| task_type | 全部设备 / 指定设备列表 / 灰度比例 |
| gray_ratio | 灰度比例（%），如 1 / 10 / 50 / 100 |
| retry_times / retry_interval_min | 失败重试策略（默认 2 次 / 5min，同华为建议） |
| download_timeout_min / upgrade_timeout_min | 超时（默认 60 / 30） |
| auto_pause_on_fail | 失败自动暂停开关 |
| schedule_time | 定时开始（默认立即） |

创建时**快照目标设备**写入 ota_task_device（state=PENDING），防止设备变更影响任务一致性。

### 6.2 灰度推进

- 灰度任务初始只下发 `round(device_count × gray_ratio%)` 台（按 device_id 升序取模）；
- **推进规则**：每批（默认间隔 30min，可配）检查已完结设备的**成功率**：
  - 成功率 ≥ 95% → 推进下一档（1% → 10% → 50% → 100%）；
  - 成功率 < 95% 或失败率突增（回滚/失败数激增）→ **自动暂停**（status=3），通知管理端人工介入；
- 全量完成后任务置「已完成」，保留 90 天后可清理。

### 6.3 重试与超时扫描（@Scheduled，xxl-job 可选）

| 扫描器 | 频率 | 逻辑 |
| --- | --- | --- |
| 超时扫描 | 1min | 下载中且超 download_timeout → 重试 / 耗尽置 TIMEOUT；升级中且超 upgrade_timeout → 同上 |
| 重试扫描 | 1min | 失败且 retry_count < retry_times 且 retry_at 已到 → 重新下发通知 |
| 灰度推进 | 5min | 按 6.2 规则推进灰度批次 |

---

## 7. 安全设计

| 层级 | 手段 | 说明 |
| --- | --- | --- |
| 传输层 | HTTPS | 升级包下载仅 HTTPS，URL 带签名时效参数（预留对象存储签名 URL） |
| 完整性 | SHA256 | 信封携带 sha256，设备下载后校验，不符拒绝安装并上报 VERIFY_FAIL |
| 校验 | MD5（兼容） | 低端 MCU 无 SHA256 硬件加速时退化为 MD5（信封携带 signMethod） |
| 防篡改 | RSA 签名验签（预留） | 上传时用厂商私钥签名，设备用预置公钥验签，验签通过才可安装 |
| 差分校验 | 双 SHA256 | 差分包自身 SHA256（下载校验）+ 合并产物 SHA256 == 全量包 sha256（targetSha256，防差分算法缺陷/篡改导致合并出错误固件） |
| 防降级 | 版本单调 | 禁止向低于当前版本的包下发（管理端校验 target > 设备当前版本） |

---

## 8. 管理端 REST 接口（网关 /api/ota）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ota/packages` | 上传升级包（multipart + 元数据表单，可指定 package_type/base_version 直接传差分包） |
| POST | `/api/ota/packages/{packageId}/diff` | 生成差分包（选源版本，平台调 bsdiff 生成并入库） |
| GET | `/api/ota/packages` | 升级包分页列表（产品/版本/类型/状态过滤） |
| GET | `/api/ota/packages/{packageId}` | 升级包详情 |
| PUT | `/api/ota/packages/{packageId}/status` | 停用/启用升级包 |
| DELETE | `/api/ota/packages/{packageId}` | 删除升级包（逻辑删，任务引用中禁止删） |
| POST | `/api/ota/tasks` | 创建批次任务 |
| GET | `/api/ota/tasks` | 任务分页列表 |
| GET | `/api/ota/tasks/{taskId}` | 任务详情（含统计：成功/失败/进行中） |
| GET | `/api/ota/tasks/{taskId}/devices` | 设备明细分页（状态过滤） |
| POST | `/api/ota/tasks/{taskId}/cancel` | 取消任务（未开始/进行中可取消） |
| POST | `/api/ota/tasks/{taskId}/resume` | 恢复暂停的任务 |
| POST | `/api/ota/tasks/{taskId}/devices/{deviceId}/retry` | 单设备重试 |
| GET | `/api/ota/tasks/{taskId}/statistics` | 版本分布/成功率统计（升级后分析，参考阿里云成功率分析） |

---

## 9. Redis Key 设计（遵循 docs/design/Redis-key规范.md）

| Key | 类型 | TTL | 说明 |
| --- | --- | --- | --- |
| `ota:version:{deviceId}` | string | 7d | 设备当前固件版本（inform 上报缓存，热路径读） |
| `ota:task:{taskId}:gray:state` | string | 任务周期 | 灰度档位（1/10/50/100），推进用 |
| `ota:task:{taskId}:device:{deviceId}` | string | 任务周期 | 任务-设备状态快照（PENDING/...），下发判重用 |
| `ota:lock:task:{taskId}` | string | 10s | 任务推进/取消分布式锁（SETNX） |

> 所有 Key 构建统一走 `OtaKeys` 工具类（模块唯一出口），新增 Key 前先补规范文档。

---

## 10. 与现有模块集成

| 集成点 | 方式 |
| --- | --- |
| 设备中心（device） | 升级成功后 Feign 调 device 回写 `firmware_version`（或直接更新本库快照 + 通知 device 刷新）；新增 `ota` 时校验设备存在 |
| 命令中心（command） | 不依赖：OTA 走独立 topic 通道，不下发物模型命令（避免与设备自身升级逻辑冲突） |
| 影子（shadow） | 可选：升级中写入影子 desired `{"ota":{"state":"UPGRADING"}}` 供展示（尽力增强，不阻塞主流程） |
| 告警中心（alarm） | 任务失败/设备升级失败 → 投递 `iot-alarm` 事件（复用告警规则引擎通知） |
| 规则引擎（rule） | 可配置"设备升级成功/失败"触发源（后续扩展） |
| 前端 | 升级包管理页（上传/列表）+ 任务管理页（创建/灰度进度/设备明细/统计图表） |

---

## 11. 前端页面

1. **OTA 升级包**：上传（文件 + 版本 + 源版本 + 描述）、列表（版本/大小/MD5/状态）、停用/删除；
2. **升级任务**：创建（选包 + 范围 + 灰度比例 + 重试/超时）、任务列表（进度条：成功/失败/进行中占比）、
   设备明细（按状态过滤 + 单设备重试）、统计（版本分布柱状图、成功率曲线）；
3. 菜单资源：父级「设备运维」下新增 `ota` 菜单（perm_code=ota:view 等），双通道 SQL（Flyway V9 + 91_ota_menu.sql）。

---

## 12. 分阶段实施计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 基础 | 模块骨架 + 三表建表 + 升级包上传/列表/校验（MD5/SHA256）+ 文件本地存储 | 管理端可上传/查询升级包，校验正确 |
| S2 协议 | OTA topic 路由（access 透传 ota.uplink）+ inform/progress/result/pull 消息处理 + 版本缓存 | 设备模拟器可上报版本/进度/结果 |
| S3 任务 | 任务 CRUD + 设备快照 + 通知下发（在线/离线补推/主动拉取）+ 成功判定 + 版本回写 | 端到端：设备从 1.0.0 升级到 2.0.0 成功 |
| S4 灰度与运维 | 灰度推进 + 失败自动暂停 + 重试/超时扫描 + 取消/恢复 + 统计 + 告警集成 + 前端页面 | 灰度任务按比例推进，失败自动暂停；前端可视化 |
| S5 差分与安全增强 | **差分升级（bsdiff 生成 + 下发匹配 + 设备端 bspatch 合并验证）+ RSA 签名验签 + 对象存储抽象（预留 OSS/本地切换）+ HTTPS 签名 URL + 分片断点续传验证** | 差分端到端（1.0.0→2.0.0 只传 diff）+ 校验/签名/续传全链路测试通过 |
| S5 安全增强 | RSA 签名验签 + 对象存储抽象（预留 OSS/本地切换）+ HTTPS 签名 URL + 分片断点续传验证 | 校验/签名/续传全链路测试通过 |

---

## 13. 风险与开放问题

| 风险/问题 | 影响 | 应对 |
| --- | --- | --- |
| 设备端无 OTA 客户端 | 协议落地依赖设备 SDK | 提供模拟器 + 参考实现（EMQX/MQTTX 协议栈） |
| 低端 MCU 内存受限 | 大固件分片下载压力 | 分片大小可配（32B-1MB），块级 SHA256 |
| 升级中断电/断网 | 设备变砖风险 | AB 分区回滚（设备端）、断点续传（传输层）、失败可重试 |
| 灰度误判 | 大面积回滚 | 自动暂停 + 成功率阈值可配 + 人工确认恢复 |
| 多模块固件 | 单模块设计扩展 | module 字段预留，后续按模块维度任务 |
| 差分算法/生成成本 | bsdiff 生成需新旧全量包 + CPU | 平台保留各版本全量包；支持厂商上传预生成差分包（省平台计算）；差分包按需生成（有任务才生成） |
| 设备端 bspatch 资源 | 低端 MCU 内存不足无法合并 | 合并需约 1.2× 固件临时区；设备能力不足时任务强制 FULL_ONLY（downloadPolicy 按产品可配） |
| 差分匹配缺失 | 多跳升级无逐跳差分 | 差分缺失自动退化全量，永不阻断升级；跨多版本直接全量 |
| 升级包存储容量 | 本地磁盘增长 | 保留策略（历史包可清理）+ 对象存储抽象 |

---

## 14. 附：消息格式示例

**版本上报（上行 ota/inform）**
```json
{"id":"msg-001","version":"1.0.0","module":"main"}
```

**升级通知（下行 {pk}/{dn}/ota/down）**
```json
{
  "taskId": "1876543210000000001",
  "version": "2.0.0",
  "packageType": "DIFF",
  "baseVersion": "1.0.0",
  "url": "https://ota.energyx.local/fw/v1.0.0-v2.0.0.diff?exp=1800&sig=...",
  "size": 1234567,
  "sha256": "f8d85b250d4d787a9f483d89a974...",
  "targetSha256": "a1b2c3d4e5f6...",
  "signMethod": "SHA256",
  "segmentSize": 1048576,
  "module": "main",
  "extData": {"ota_notice": "升级 BMS 保护逻辑"}
}
```

**进度上报（上行 ota/progress）**
```json
{"id":"msg-002","taskId":"1876543210000000001","progress":45,"state":"DOWNLOADING"}
```

**结果上报（上行 ota/result）**
```json
{"id":"msg-003","taskId":"1876543210000000001","success":true,"version":"2.0.0","code":0,"msg":"ok"}
```

**失败上报（上行 ota/result）**
```json
{"id":"msg-004","taskId":"1876543210000000001","success":false,"version":"1.0.0","code":4001,"msg":"SHA256 mismatch"}
```
