package com.energyx.common.tenant;

/**
 * 租户上下文（ThreadLocal）。
 *
 * <p>由 {@link TenantContextFilter} 在 HTTP 请求进入时从网关透传头注入，请求结束必须 clear。
 * 普通 ThreadLocal（非 inheritable）：Kafka 消费线程 / @Scheduled 调度线程 / Netty 连接线程
 * 不继承请求上下文，因此天然落在「无租户上下文」状态，租户拦截器据此跳过全部表。
 */
public final class TenantContext implements AutoCloseable {

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 获取上下文句柄，配合 try-with-resources 在作用域结束时自动 clear。 */
    public static TenantContext acquire() {
        return new TenantContext();
    }

    public static void set(TenantInfo info) {
        HOLDER.set(info);
    }

    /** 是否存在可用租户身份（tenantId 非空）。 */
    public static boolean hasTenant() {
        TenantInfo info = HOLDER.get();
        return info != null && info.tenantId() != null;
    }

    public static Long getTenantId() {
        TenantInfo info = HOLDER.get();
        return info == null ? null : info.tenantId();
    }

    public static Long getEnterpriseId() {
        TenantInfo info = HOLDER.get();
        return info == null ? null : info.enterpriseId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    @Override
    public void close() {
        clear();
    }
}
