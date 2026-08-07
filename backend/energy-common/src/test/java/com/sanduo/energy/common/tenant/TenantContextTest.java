package com.sanduo.energy.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户上下文生命周期测试：set / hasTenant / get / clear，确保 ThreadLocal 不跨测试泄漏。
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void emptyByDefault() {
        assertFalse(TenantContext.hasTenant());
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getEnterpriseId());
    }

    @Test
    void setAndRead() {
        TenantContext.set(new TenantInfo(3L, 7L));
        assertTrue(TenantContext.hasTenant());
        assertEquals(3L, TenantContext.getTenantId());
        assertEquals(7L, TenantContext.getEnterpriseId());
    }

    @Test
    void setWithNullTenant_shouldNotBeHasTenant() {
        TenantContext.set(new TenantInfo(null, 7L));
        assertFalse(TenantContext.hasTenant());
        assertNull(TenantContext.getTenantId());
        assertEquals(7L, TenantContext.getEnterpriseId());
    }

    @Test
    void clearResets() {
        TenantContext.set(new TenantInfo(1L, null));
        TenantContext.clear();
        assertFalse(TenantContext.hasTenant());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void autoCloseClears() {
        try (TenantContext ignored = TenantContext.acquire()) {
            TenantContext.set(new TenantInfo(2L, 9L));
            assertTrue(TenantContext.hasTenant());
        }
        assertFalse(TenantContext.hasTenant());
    }
}
