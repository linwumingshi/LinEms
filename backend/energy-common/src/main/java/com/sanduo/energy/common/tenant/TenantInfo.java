package com.sanduo.energy.common.tenant;

/**
 * 当前请求的租户身份（由网关透传头注入，业务服务不信任客户端自报身份）。
 *
 * @param tenantId     租户 ID（x-tenant-id）
 * @param enterpriseId 企业 ID（x-enterprise-id，可空）
 */
public record TenantInfo(Long tenantId, Long enterpriseId) {
}
