# 设备生命周期状态机完善（管理态 + 封禁回写）

> 日期：2026-08-12 ｜ 状态：待实施
> 触发：用户反馈"设备状态不应直接编辑，应有整体流程"。

---

## 一、现状盘点（已确认）

**状态定义**（`Constants`，与 `iot_device.status` 注释一致）：
| 值 | 含义 | 维护方 |
|---|---|---|
| 0 | 未注册 | 创建时 |
| 1 | 未激活 | 创建后（有凭据但未激活） |
| 2 | 已激活(离线) | **access LifecycleProcessor 已自动维护**（OFFLINE 事件） |
| 3 | 在线 | **access LifecycleProcessor 已自动维护**（ONLINE 事件） |
| 4 | 禁用 | 无任何代码写入（缺口） |
| 5 | 封禁 | broker 认证失败触发 Redis 封禁，但**不回写 DB**（缺口） |

**已确认的现状**：
- ✅ **运行态（2/3）联动已完整实现**：`LifecycleProcessor` 消费 `iot-device-lifecycle` → `DeviceStatusMapper.updateOnline/updateOffline`（含累计在线秒数、审计记录、离线补发）
- ❌ **管理态可任意编辑**：`DeviceUpdateReq.status` 字段 + `DeviceServiceImpl.update` 直接 `setStatus(req.getStatus())`，无流转校验（0↔5 任意跳）
- ❌ **禁用（4）无入口**：没有任何代码/接口写 4
- ❌ **封禁（5）不落库**：`DeviceAuthService.recordFailureAndMaybeBan` 只写 Redis `mqtt:ban:*`，不发事件、不写设备表
- ❌ **前端表单直接编辑 status**：`Device.vue` create/update 表单 status 下拉可任意选

## 二、目标状态机

```
创建 0 ──登记──▶ 1 未激活 ──activate──▶ 2 已激活(离线) ⇄ 3 在线（系统自动）
                     │                      │
                     │                      │ disable
                     ▼                      ▼
                 4 禁用 ◀──────────────┐
                  │  enable              │
                  └───────────────────▶ 2
                  
 5 封禁：认证失败×N（broker 自动触发，TTL 解封）→ 2
```

**流转规则（状态机校验）**：
| 动作 | 来源状态 | 目标状态 | 说明 |
|---|---|---|---|
| activate（激活） | 1（或 0→先登记） | 2 | 管理员操作 |
| disable（禁用） | 2、3 | 4 | 管理员操作；在线时禁用需先踢线 |
| enable（启用） | 4 | 2 | 管理员操作 |
| ban（封禁） | 2、3 | 5 | broker 认证失败自动触发 |
| unban（解封） | 5 | 2 | TTL 自动解封 / 管理员手动 |
| 上线/下线 | 2⇄3 | — | 系统自动（已有） |

非法流转（如 0→4、1→3、4→5）一律拒绝。

## 三、改造清单

### 后端 energy-device（管理态状态机）
| 文件 | 改动 |
|---|---|
| `DeviceUpdateReq` | **移除 `status` 字段**（update 不再能改状态） |
| `DeviceServiceImpl` | `update` 删除 `setStatus`；新增 `activate(deviceId)` / `disable(deviceId)` / `enable(deviceId)` 动作，每个做状态机校验 |
| `DeviceService` | 接口加 3 个动作方法 |
| `DeviceController` | 加 `POST /{id}/activate`、`POST /{id}/disable`、`POST /{id}/enable` |
| `ErrorCode`（common） | 加 `DEVICE_STATUS_INVALID` |

### 后端 energy-mqtt-broker（封禁事件发布）
| 文件 | 改动 |
|---|---|
| `DeviceAuthService` | 注入 KafkaProducer；`recordFailureAndMaybeBan` 达到阈值时发 `iot-device-lifecycle` 的 **BANNED** 事件（含 deviceId） |

### 后端 energy-access（封禁回写 + 解封）
| 文件 | 改动 |
|---|---|
| `DeviceStatusMapper` | 加 `updateBanned(deviceId)`（status=5）、`updateUnbanned(deviceId)`（status=2） |
| `LifecycleProcessor` | `process` 增加 BANNED/UNBANNED 分支 |

### 前端 Device.vue
| 改动 |
|---|
| 表单移除 status 编辑（create 默认 0，update 不改状态） |
| 行操作加：激活（status=1 时）、禁用（2/3 时）、启用（4 时）按钮 |

## 四、验证
1. 单测：状态机校验（合法/非法流转矩阵）、activate/disable/enable、LifecycleProcessor BANNED 分支
2. 冒烟：创建设备→激活→（模拟）在线→禁用→启用；认证失败 N 次→设备表变 5→TTL 解封回 2
3. 回归：device/access/broker 全量单测

## 五、边界与风险
- **禁用在线设备**：disable 时若 status=3 在线，需先通过 Redis 踢线（复用 mqtt:conn 锁/踢线逻辑）——本期先置 4 + 发 revoke 广播踢线（broker 收到 revoke 会踢在线连接，凭据失效缓存兜底）
- **封禁回写与运行态竞争**：BANNED 写 5 后，若设备正在线，下一次 ONLINE 事件可能把状态改回 3——需要 LifecycleProcessor 处理 ONLINE 时**跳过封禁/禁用态**（只允许 2→3）
- **封禁不落库的存量语义**：Redis 封禁是权威（TTL 解封），DB 的 5 是"审计视图"；unban 由 TTL 自然过期后的下一次成功认证触发回写，或管理员手动解封
