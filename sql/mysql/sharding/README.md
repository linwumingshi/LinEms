# ShardingSphere 分表模板（生产物理表）

## 作用

`sql/mysql/` 下 00~80 为**逻辑基表**（单实例开发/演示可直接执行）。生产环境按 ADR-006 分库分表，物理表由部署初始化脚本基于本目录模板生成，ShardingSphere **不自动建表**。

## 分片规划

| 表 | 分片键 | 分片数 | 分库 | 说明 |
| --- | --- | --- | --- | --- |
| `iot_device` | `device_id` hash | 16 表 × N 库 | tenant_id 分库 | 单设备读写同分片 |
| `iot_device_credential` | `device_id` hash | 16 表 × N 库 | tenant_id 分库 | 与设备同片 |
| `iot_device_online_record` | `device_id` hash | 16 表 × N 库 | tenant_id 分库 | 时间分区仅演示 |
| `iot_shadow` | `device_id` hash | 16 表 × N 库 | tenant_id 分库 | PK=分片键 |
| `iot_command` | `device_id` hash | 16 表 × N 库 | tenant_id 分库 | 同设备指令串行 |
| `sys_operator_log` | 时间 | 按月 | — | MySQL 原生分区，见 10_sys.sql |
| `iot_alarm_record` | 时间 | 按月 | — | MySQL 原生分区，见 60_alarm.sql |
| `iot_command_ack` | 时间 | 按月 | — | MySQL 原生分区，见 50_command.sql |

## 生成方式

部署脚本按模板循环生成物理表（示例 `snd` 库 16 片）：

```bash
for i in $(seq 0 15); do
  sed -e "s/__SUFFIX__/${i}/" templates/iot_device.@.sql \
      -e "s/__DB__/es_device_${i % 2}/" | mysql
done
```

模板文件占位符：
- `__SUFFIX__` → 分片序号 `_0` ~ `_15`
- `__DB__`     → 物理库名（按 tenant 前缀）

## 注意

- **分片键不可变**：device_id 在注册后不得变更，否则历史数据无法路由；
- **跨分片聚合**：跨分片 JOIN/COUNT 受限，由 ES/TDengine/报表服务兜底；
- 本目录模板由 `deploy/scripts/init-sharding.sh` 引用（Phase 3 落地）。
