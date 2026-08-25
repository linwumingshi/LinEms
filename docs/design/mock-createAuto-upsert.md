# 模拟设备自动建档幂等化（upsert）设计

## 背景 / 问题
- 模拟设备页「自动建档」调用 `SimulatorService.createAuto` → `deviceFeignClient.create(req)`，撞 `iot_device` 的
  `(tenant_id, product_key, device_name)` 唯一约束时，device 服务返回「设备已存在」，被包成「创建设备失败」抛给前端；
  而 mock 内存注册表尚未 `put`，导致**「模拟列表空却报已存在」**。
- 根因：删除模拟设备只清内存（`remove` 仅 `devices.remove` + 断链），真实设备残留 MySQL；重启 mock 仅清内存、动不到 MySQL，故重启无效。
- 语义错配：模拟器本意是「用某设备身份连 broker 仿真」，真实设备是否已存在**不该阻断仿真**。

## 方案
将 `createAuto` 改为 upsert（存在即复用，不存在才建）：
1. 新增 Feign 方法 `byName(productKey, deviceName)` → `GET /device/by-name`（device 服务已有，返回 `Result<Device>`），
   mock 侧用轻量 DTO `DeviceBrief{ Long deviceId }` 接收。
2. 新增私有方法 `resolveDeviceId(...)`：先 `byName` 查，命中复用 `deviceId`；未命中再 `create`。
3. `createAuto` 调用 `resolveDeviceId` 取代原直接 `create`。
4. **不改 `remove`**：残留真实设备变为「可复用」而非「冲突源」，upsert 后不再报错，避免级联删真实设备带来的业务数据风险。

## 影响面
- auto 模式对「已存在真实设备」变为：复用 + 重生成密钥（既有 auto 行为不变）。注意会重生成该真实设备密钥并踢其在线连接（测试设备场景可接受）。
- takeover 模式不受影响。

## 改动文件
- `energy-mock-device/src/main/java/com/energyx/mock/client/DeviceFeignClient.java`：加 `byName` + import。
- `energy-mock-device/src/main/java/com/energyx/mock/client/dto/DeviceBrief.java`：新增。
- `energy-mock-device/src/main/java/com/energyx/mock/service/SimulatorService.java`：`createAuto` → upsert。

## 验证
1. `mvn -pl energy-mock-device -am compile`（含 `spring-javaformat:validate`）。
2. 重启 mock，前端「模拟设备」选产品 `testMeter` → 设备名 `eee` 重建，不再报「已存在」；设备下拉应选中原 `testMeter_eee` 并成功上线。
