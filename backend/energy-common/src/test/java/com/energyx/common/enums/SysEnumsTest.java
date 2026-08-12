package com.energyx.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** system 模块业务枚举统一测试：code/desc/fromCode/of 语义与 DB 存量值一致（P2 枚举化 Task 3） */
class SysEnumsTest {

	@Test
	void userStatus_codesAndLookup() {
		assertEquals(0, UserStatus.DISABLED.getCode());
		assertEquals(1, UserStatus.ENABLED.getCode());
		assertEquals(2, UserStatus.LOCKED.getCode());
		assertEquals("启用", UserStatus.ENABLED.getDesc());
		assertEquals(UserStatus.ENABLED, UserStatus.fromCode(1));
		assertEquals(UserStatus.LOCKED, UserStatus.of(2));
		assertNull(UserStatus.of(99));
		assertNull(UserStatus.of(null));
		assertThrows(IllegalArgumentException.class, () -> UserStatus.fromCode(9));
	}

	@Test
	void roleStatus_codesAndLookup() {
		assertEquals(0, RoleStatus.DISABLED.getCode());
		assertEquals(1, RoleStatus.ENABLED.getCode());
		assertEquals("禁用", RoleStatus.DISABLED.getDesc());
		assertEquals(RoleStatus.ENABLED, RoleStatus.fromCode(1));
		assertEquals(RoleStatus.DISABLED, RoleStatus.of(0));
		assertNull(RoleStatus.of(null));
	}

	@Test
	void permissionStatus_codesAndLookup() {
		assertEquals(0, PermissionStatus.NORMAL.getCode());
		assertEquals(1, PermissionStatus.DISABLED.getCode());
		assertEquals("正常", PermissionStatus.NORMAL.getDesc());
		assertEquals("停用", PermissionStatus.DISABLED.getDesc());
		assertEquals(PermissionStatus.DISABLED, PermissionStatus.fromCode(1));
		assertEquals(PermissionStatus.NORMAL, PermissionStatus.of(0));
		assertNull(PermissionStatus.of(7));
	}

	@Test
	void tenantStatus_codesAndLookup() {
		assertEquals(0, TenantStatus.DISABLED.getCode());
		assertEquals(1, TenantStatus.ENABLED.getCode());
		assertEquals(TenantStatus.ENABLED, TenantStatus.fromCode(1));
		assertEquals(TenantStatus.DISABLED, TenantStatus.of(0));
		assertNull(TenantStatus.of(null));
	}

	@Test
	void dataScope_codesAndLookup() {
		assertEquals(1, DataScope.SELF.getCode());
		assertEquals(2, DataScope.ENTERPRISE.getCode());
		assertEquals(3, DataScope.TENANT.getCode());
		assertEquals(4, DataScope.ALL.getCode());
		assertEquals("本企业", DataScope.ENTERPRISE.getDesc());
		assertEquals(DataScope.ALL, DataScope.fromCode(4));
		assertEquals(DataScope.TENANT, DataScope.of(3));
		assertNull(DataScope.of(0));
		assertNull(DataScope.of(null));
	}

	@Test
	void enterpriseLevel_codesAndLookup() {
		assertEquals(1, EnterpriseLevel.GROUP.getCode());
		assertEquals(2, EnterpriseLevel.SUB.getCode());
		assertEquals("集团直属", EnterpriseLevel.GROUP.getDesc());
		assertEquals("子企业", EnterpriseLevel.SUB.getDesc());
		assertEquals(EnterpriseLevel.SUB, EnterpriseLevel.fromCode(2));
		assertEquals(EnterpriseLevel.GROUP, EnterpriseLevel.of(1));
		assertNull(EnterpriseLevel.of(3));
	}

}
