# EnergyX 储能管理平台（EMS）

面向新能源储能行业的企业级 IoT + EMS 平台（虚拟项目）。覆盖 集团→企业→电站→储能柜→电池簇→PCS→BMS→电芯 全链路资产管理，支持百万级设备接入、多租户、云边协同与 AI 能源优化。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 接入层 | 自研 Netty MQTT Broker（MQTT 3.1.1/5.0），Redis 会话共享，Kafka 跨节点路由 |
| 后端 | Java 17 / Spring Boot 3.x / Spring Cloud Alibaba / Spring Security（RBAC + Redis 会话令牌） / MyBatis Plus |
| 消息 | Kafka（15 topic，全链路事件总线） |
| 存储 | MySQL 8（ShardingSphere 分库分表）/ TDengine（时序）/ Redis Cluster / Elasticsearch |
| 服务治理 | Nacos / Spring Cloud Gateway / OpenFeign / Sentinel |
| 前端 | Vue3 / TypeScript / Vite / Pinia / Element Plus / ECharts |

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 1 | 整体架构设计（七层 + 自研 Broker 集群 + 高可用扩容） | ✅ 完成 |
| Phase 2 | 数据库设计（MySQL/TDengine/ES/Redis/Kafka 全量 + DDL） | ✅ 完成 |
| Phase 3 | 后端工程初始化（Maven 多模块 + 依赖环境） | ✅ 完成 |
| Phase 4 | 设备接入模块（自研 MQTT Broker） | ✅ 完成 |
| Phase 5 | 消息处理模块（接入适配 + 时序摄取） | ✅ 完成 |
| Phase 6 | 业务模块（影子/指令/告警/策略引擎） | ✅ 完成 |
| Phase 7 | 前端开发（Vue3 驾驶舱等） | ✅ 完成 |
| Phase 8 | 测试与压力测试（百万连接/故障演练） | ✅ 完成 |
| Phase 9 | 生产化差距分析（缺陷追踪 + 路线图）＋ P0-1 网关鉴权落地（JWT 验签 + Spring Security RBAC + 用户/角色/菜单/单位管理模块） | ✅ 完成 |

## 文档导航

- 架构设计：`docs/design/Phase1-整体架构设计.md`
- 数据库设计：`docs/design/Phase2-数据库设计.md`
- 后端工程初始化：`docs/design/Phase3-后端工程初始化.md`
- 设备接入（自研 MQTT Broker）：`docs/design/Phase4-自研MQTTBroker.md`
- 消息处理模块：`docs/design/Phase5-消息处理模块.md`
- 业务模块（影子/指令/告警）：`docs/design/Phase6-业务模块.md`
- 前端开发（Vue3 驾驶舱）：`docs/design/Phase7-前端开发.md`
- 测试与压力测试（SDK/压测/演练）：`docs/design/Phase8-测试与压力测试.md`
- 生产化差距分析（缺陷追踪 + 路线图）：`docs/design/Phase9-生产化差距分析.md`
- Redis Key 规范：`docs/design/Redis-key规范.md`
- 模拟设备接入与消息验证指南：`docs/sim-device-使用验证指南.md`
- 技术决策记录（ADR）：`docs/decisions/ADR-技术决策记录.md`
- 管理后台页面设计：`docs/superpowers/specs/2026-08-08-admin-pages-design.md`
- MySQL DDL：`sql/mysql/`（分域 00~80 + `sharding/` 分表模板）
- TDengine DDL：`sql/tdengine/`
- Elasticsearch：`sql/elasticsearch/`

## 快速启动（全栈）

```bash
# 1) 构建后端（含 SDK/压测工具；仅首次或改码后）
cd backend && mvn -Dmaven.repo.local="/d/Program Files/maven-repo" package -DskipTests

# 2) 启动全栈（Git Bash）：Docker 基础环境 + MySQL 校验 + 11 个后端服务 + 就绪轮询
deploy/scripts/start-stack.sh          # 首次构建缺失 jar 后再启动
deploy/scripts/status-stack.sh         # 查看服务/端口/基础环境/Broker 统计
deploy/scripts/stop-stack.sh           # 停止后端（--infra 连带停 Docker）

# 3) 故障演练（需全栈在跑）
cd test/drill && ./run-all.sh
```

> 前置：Docker Desktop（Nacos/Kafka/Redis/ES/TDengine）、本机 MySQL 服务（127.0.0.1:3306，root/***，见 `deploy/env/local.env`）、Java 17。

### 端口分配（唯一性已校验）

| 端口 | 服务 | 端口 | 服务 |
| --- | --- | --- | --- |
| 8000 | energy-gateway | 8111 | energy-access |
| 8101 | energy-system | 8112 | energy-tsdb |
| 8102 | energy-product | 8113 | energy-shadow |
| 8103 | energy-device | 8114 | energy-command |
| 8104 | energy-station | 8115 | energy-alarm |
| 8105 | energy-ems | | |
| 1883 | MQTT 设备接入 | 8082 | Broker 统计 HTTP |
| 8848/9848 | Nacos | 9092 | Kafka |
| 6379 | Redis | 9200 | Elasticsearch |
| 6030 | TDengine | 3306 | MySQL |

> Phase 5/6 服务（access/tsdb/shadow/command/alarm）在 Phase 8 由 8101~8105 调整为 8111~8115，
> 消除与业务服务（system/product/device/station）的同端口冲突。

## 目录结构

```text
Energy Storage IoT Platform/
├── docs/          # 设计文档（design/ + decisions/）
├── sql/           # 数据库 DDL 脚本（mysql/tdengine/elasticsearch）
├── backend/       # 后端微服务（Phase 3 起创建；含 energy-ems 策略引擎）
├── frontend/      # 前端（Phase 7 起创建）
├── edge/          # 边缘网关程序
├── sdk/           # 设备端 MQTT SDK
├── deploy/        # 部署与本地环境（docker-compose + 启动/停止/状态脚本）
└── test/          # 压测与故障演练
```
