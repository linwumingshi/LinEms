package com.energyx.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * 条件化租户处理：仅当 {@link TenantContext} 存在时对带 tenant_id 列的表追加租户条件。
 *
 * <ul>
 *   <li>无租户上下文（Kafka 消费 / @Scheduled / Netty 认证等非 HTTP 线程）→ 全部表忽略，不拼租户条件；</li>
 *   <li>有上下文但表无 tenant_id 列（或为租户主表自身）→ 忽略；</li>
 *   <li>有上下文且有 tenant_id 列 → 追加 {@code tenant_id = 当前租户}。</li>
 * </ul>
 */
public class ConditionalTenantLineHandler implements TenantLineHandler {

    /** 无 tenant_id 列的表 + 特殊表（租户主表自身），命中则跳过租户改写 */
    public static final Set<String> NO_TENANT_COLUMN_TABLES = Set.of(
            "sys_tenant",
            "sys_user_role", "sys_permission", "sys_role_permission",
            "iot_device_certificate", "iot_device_group_relation", "iot_device_tag",
            "iot_shadow_history", "iot_command_ack", "iot_station_device");

    @Override
    public Expression getTenantId() {
        // 正常情况下仅在 hasTenant()==true 且表未忽略时被调用；兜底返回 0
        return new LongValue(TenantContext.hasTenant() ? TenantContext.getTenantId() : 0L);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (!TenantContext.hasTenant()) {
            return true;
        }
        return NO_TENANT_COLUMN_TABLES.contains(tableName.toLowerCase());
    }
}
