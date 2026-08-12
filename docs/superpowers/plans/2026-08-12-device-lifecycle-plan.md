# 设备生命周期状态机 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善设备状态生命周期——移除 update 直接改状态，新增管理态动作接口（activate/disable/enable）+ 状态机校验，broker 封禁回写设备表，前端改按钮操作。

**Architecture:** 三模块协作：energy-device 提供管理态状态机（动作接口+校验）；energy-mqtt-broker 认证失败达阈值发 BANNED 事件；energy-access 消费该事件回写设备表（含解封）。运行态 2⇄3 联动已有（LifecycleProcessor），本期不动。

**Tech Stack:** Spring Boot / MyBatis-Plus / Kafka / Vue3 + Element Plus

## Global Constraints

- 遵循阿里巴巴规范：中文注释、无行尾注释；spring-javaformat 0.0.47（改后 `mvn spring-javaformat:apply`）
- 状态常量复用 `Constants.DEVICE_STATUS_*`（0未注册 1未激活 2已激活离线 3在线 4禁用 5封禁）
- 新增 Redis key/通道必须先补 `docs/design/Redis-key规范.md`；Kafka topic 复用现有 `IOT_DEVICE_LIFECYCLE`
- 编译测试：`mvn -o test -pl <module> -Dmaven.repo.local=D:\Program Files\maven-repo`

---

### Task 1: energy-device 管理态状态机（动作接口 + 校验）

**Files:**
- Modify: `backend/energy-common/src/main/java/com/energyx/common/exception/ErrorCode.java`（加 DEVICE_STATUS_INVALID）
- Modify: `backend/energy-device/src/main/java/com/energyx/device/web/dto/DeviceUpdateReq.java`（删 status 字段）
- Modify: `backend/energy-device/src/main/java/com/energyx/device/service/DeviceService.java`（加 3 动作方法）
- Modify: `backend/energy-device/src/main/java/com/energyx/device/service/impl/DeviceServiceImpl.java`（update 删 setStatus + 3 动作实现）
- Modify: `backend/energy-device/src/main/java/com/energyx/device/web/DeviceController.java`（加 3 端点）
- Test: `backend/energy-device/src/test/java/com/energyx/device/service/impl/DeviceServiceImplTest.java`（加状态机用例）

**Interfaces:**
- Consumes: `DeviceService.getById`（存在校验）、`Constants.DEVICE_STATUS_*`
- Produces: `DeviceService.activate/disable/enable(Long deviceId)` 抛 `BusinessException(DEVICE_STATUS_INVALID)`；Controller 端点 `POST /{id}/activate` 等

- [ ] **Step 1: 写失败测试（状态机校验矩阵）**

`DeviceServiceImplTest` 新增：
```java
@Test
void activate_fromInactive_shouldSucceed() { /* status=1 → 2 */ }
@Test
void activate_fromUnregistered_shouldReject() { /* status=0 → 抛 DEVICE_STATUS_INVALID */ }
@Test
void disable_fromActiveOrOnline_shouldSucceed() { /* 2 或 3 → 4 */ }
@Test
void disable_fromUnregistered_shouldReject() { /* 0 → 抛 */ }
@Test
void enable_fromDisabled_shouldSucceed() { /* 4 → 2 */ }
```
用 spy + doReturn(getById) + mock updateById（`DeviceServiceImpl` extends ServiceImpl，updateById 可 mock）。
Run: `mvn -o test -pl energy-device -Dtest=DeviceServiceImplTest` — 期望 FAIL（方法不存在）

- [ ] **Step 2: ErrorCode 加 DEVICE_STATUS_INVALID**

`ErrorCode.java` 加：`DEVICE_STATUS_INVALID(40003, "设备状态流转不合法")`（code 对齐现有 4000x 段）。

- [ ] **Step 3: DeviceUpdateReq 删 status + update 删 setStatus**

`DeviceUpdateReq` 删 `private Integer status;`
`DeviceServiceImpl.update` 删 `update.setStatus(req.getStatus());` 及其 revoke 分支（status 不再可变；保留 deviceName 变更的 revoke）。

- [ ] **Step 4: 实现 3 个动作方法 + 接口 + Controller**

```java
// DeviceService 接口
void activate(Long deviceId);
void disable(Long deviceId);
void enable(Long deviceId);
```
实现（DeviceServiceImpl）：
```java
@Override
public void activate(Long deviceId) {
    Device dev = requireDevice(deviceId);
    // 未注册(0)或未激活(1) → 激活(2)；其余拒绝
    if (dev.getStatus() == null || (dev.getStatus() != DEVICE_STATUS_UNREGISTERED && dev.getStatus() != DEVICE_STATUS_INACTIVE)) {
        throw new BusinessException(ErrorCode.DEVICE_STATUS_INVALID, "仅未注册/未激活设备可激活");
    }
    lambdaUpdate().set(Device::getStatus, DEVICE_STATUS_INACTIVE + 1 /*=2*/).eq(Device::getDeviceId, deviceId).update();
    publishCredentialRevoked(dev.getProductKey(), dev.getDeviceName());
}
// disable: 仅 2/3 → 4；enable: 仅 4 → 2（同样先校验再 update + revoke）
```
Controller：`@PostMapping("/{deviceId}/activate")` 等 3 个，返回 `Result<Void>`。

- [ ] **Step 5: 测试通过 + 提交**

Run: `mvn -o test -pl energy-device` 全绿 → commit `feat(device): 设备管理态状态机（activate/disable/enable + 流转校验）`

### Task 2: broker 封禁事件发布 + access 回写

**Files:**
- Modify: `backend/energy-mqtt-broker/src/main/java/com/energyx/broker/auth/DeviceAuthService.java`（注入 producer，封禁时发事件）
- Modify: `backend/energy-access/src/main/java/com/energyx/access/mapper/DeviceStatusMapper.java`（加 updateBanned/updateUnbanned）
- Modify: `backend/energy-access/src/main/java/com/energyx/access/lifecycle/LifecycleProcessor.java`（处理 BANNED/UNBANNED + ONLINE 跳过禁用/封禁态）
- Test: `backend/energy-mqtt-broker/src/test/java/com/energyx/broker/auth/DeviceAuthServiceTest.java`（封禁发事件）、`backend/energy-access/src/test/.../LifecycleProcessorTest.java`

**Interfaces:**
- Consumes: `KafkaTopicConstant.IOT_DEVICE_LIFECYCLE`、broker `KafkaProducer`（确认 bean 名与 send 签名）
- Produces: lifecycle 消息 `eventType=BANNED/UNBANNED`（LifecycleMessage 需确认可承载）→ access `LifecycleProcessor.process`

- [ ] **Step 1: 查 LifecycleMessage 结构 + broker producer bean**

```bash
grep -n "class LifecycleMessage\|private" backend/energy-common/src/main/java/com/energyx/common/message/LifecycleMessage.java
grep -rn "class KafkaProducer\|send(" backend/energy-mqtt-broker/src/main/java/com/energyx/broker/mqtt/*.java
```
确认 eventType 是否支持 BANNED；producer 方法签名（send(topic, key, payload) 或 sendBytes）。

- [ ] **Step 2: broker 封禁发 BANNED 事件**

`DeviceAuthService.recordFailureAndMaybeBan` 达阈值时（banClient 后）发：
```java
producer.send(KafkaTopicConstant.IOT_DEVICE_LIFECYCLE, clientId,
    LifecycleMessage.banned(deviceId, tenantId, "AUTH_FAIL_EXCEED"));
```
clientId → deviceId 解析：注入 DeviceMapper 查（或复用现有 cache:cred）。**若 deviceId 解析成本高，退化为仅发 clientId，access 侧按 clientId 解析**——实施时确认。

- [ ] **Step 3: DeviceStatusMapper 加 2 方法**

```java
@Update("UPDATE iot_device SET status = 5 WHERE device_id = #{deviceId} AND status IN (2,3)")
int updateBanned(@Param("deviceId") long deviceId);
@Update("UPDATE iot_device SET status = 2 WHERE device_id = #{deviceId} AND status = 5")
int updateUnbanned(@Param("deviceId") long deviceId);
```

- [ ] **Step 4: LifecycleProcessor 加分支 + ONLINE 保护**

`process` 加：`"BANNED".equals(eventType) → updateBanned`；`"UNBANNED" → updateUnbanned`。
`handleOnline` 加保护：**仅当当前状态是 2（或 3）才置 3**（`AND status IN (2,3)`），避免禁用/封禁设备上线被 ONLINE 事件覆盖回 3。对应改 `DeviceStatusMapper.updateOnline` WHERE 加 `status IN (2,3)`。

- [ ] **Step 5: 测试 + 提交**

Run: broker + access 全测 → commit `feat: 认证封禁回写设备表（BANNED/UNBANNED 事件链路）`

### Task 3: 前端状态按钮化 + 联调冒烟

**Files:**
- Modify: `frontend/src/views/Device.vue`
- Modify: `frontend/src/api/device.ts`（加 3 个动作 API）
- Test: vue-tsc + 浏览器冒烟

**Interfaces:**
- Consumes: Task 1 的 `POST /api/device/{id}/activate|disable|enable`（网关 StripPrefix）
- Produces: 行操作按钮按状态显隐

- [ ] **Step 1: device.ts 加 API**

```ts
activate(id: number) { return http.post(`/api/device/${id}/activate`) },
disable(id: number) { return http.post(`/api/device/${id}/disable`) },
enable(id: number) { return http.post(`/api/device/${id}/enable`) },
```

- [ ] **Step 2: Device.vue 表单删 status + 行操作按钮**

表单删 status 下拉（create 固定 0，不传 update status）。
行操作列（width 扩到 240）加条件按钮：
```vue
<el-button v-if="row.status === 1" link type="primary" @click.stop="setState(row, 'activate')">激活</el-button>
<el-button v-if="row.status === 2 || row.status === 3" link type="warning" @click.stop="setState(row, 'disable')">禁用</el-button>
<el-button v-if="row.status === 4" link type="success" @click.stop="setState(row, 'enable')">启用</el-button>
```
`setState` 调对应 API + ElMessage + reload。

- [ ] **Step 3: vue-tsc + 冒烟**

`cd frontend && ./node_modules/.bin/vue-tsc --noEmit` 通过。
冒烟：创建设备(status 0/1) → 列表点激活 → 状态变 2 → 禁用 → 4 → 启用 → 2。

- [ ] **Step 4: 提交**

`git commit -m "feat(frontend): 设备状态按钮化（激活/禁用/启用）+ 表单移除状态编辑"`

---

## Self-Review

- **Spec 覆盖**：Task 1=管理态状态机（update 删 status + 3 动作 + 校验）；Task 2=封禁回写（broker 事件 + access 落库 + ONLINE 保护）；Task 3=前端按钮。覆盖 spec 全部 3 项改造。✅
- **占位符扫描**：activate 状态目标写为 `DEVICE_STATUS_INACTIVE + 1`（=2）——**不规范，应直接用 `Constants.DEVICE_STATUS_OFFLINE`（=2，语义"已激活离线"）**，实施时统一。已记录。
- **类型一致性**：LifecycleMessage 的 BANNED 事件需确认 eventType 字段可扩展（Step 1 查）；producer 签名待确认。⚠️ 两处接口事实在 Task 2 Step 1 先行确认。
- **风险**：broker 封禁发事件需要 deviceId——clientId=`{pk}_{dn}` 解析 deviceId 需查 DB/缓存，Task 2 Step 2 有降级方案（access 侧按 clientId 解析）。
