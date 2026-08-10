package com.energyx.system.service.impl;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.system.dto.SysPermissionSaveReq;
import com.energyx.system.entity.SysPermission;
import com.energyx.system.mapper.SysPermissionMapper;
import com.energyx.system.mapper.SysRolePermissionMapper;
import com.energyx.system.security.PermissionResolver;
import com.energyx.system.security.TokenService;
import com.energyx.system.service.SysPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单资源管理服务单元测试：权限标识唯一、成环防护、删除约束、写操作后全量刷新会话。
 */
@ExtendWith(MockitoExtension.class)
class SysPermissionServiceImplTest {

	@Mock
	private SysPermissionMapper permissionMapper;

	@Mock
	private SysRolePermissionMapper rolePermissionMapper;

	@Mock
	private TokenService tokenService;

	@Mock
	private PermissionResolver permissionResolver;

	private SysPermissionService service;

	@BeforeEach
	void setUp() {
		SysPermissionServiceImpl impl = new SysPermissionServiceImpl(rolePermissionMapper, tokenService,
				permissionResolver);
		ReflectionTestUtils.setField(impl, "baseMapper", permissionMapper);
		service = impl;
	}

	private SysPermissionSaveReq menuReq(String code) {
		SysPermissionSaveReq req = new SysPermissionSaveReq();
		req.setPermCode(code);
		req.setPermName("菜单" + code);
		req.setPermType(1);
		return req;
	}

	private SysPermission perm(Long id, Long parentId, String code) {
		SysPermission p = new SysPermission();
		p.setPermId(id);
		p.setParentId(parentId);
		p.setPermCode(code);
		p.setPermName("菜单" + code);
		p.setPermType(1);
		p.setStatus(0);
		return p;
	}

	@Test
	void create_success_setsDefaultsAndRefreshesAll() {
		when(permissionMapper.selectCount(any())).thenReturn(0L);
		when(permissionMapper.insert(any(SysPermission.class))).thenReturn(1);

		service.createPermission(menuReq("system:user:list"));

		ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
		verify(permissionMapper).insert(captor.capture());
		assertEquals(0L, captor.getValue().getParentId());
		assertEquals(0, captor.getValue().getSort());
		assertEquals(0, captor.getValue().getVisible());
		assertEquals(0, captor.getValue().getStatus());
		verify(tokenService).refreshAllSessions(permissionResolver);
	}

	@Test
	void create_duplicateCode_conflict() {
		when(permissionMapper.selectCount(any())).thenReturn(1L);

		BusinessException ex = assertThrows(BusinessException.class,
				() -> service.createPermission(menuReq("system:user:list")));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
		verify(permissionMapper, never()).insert(any(SysPermission.class));
	}

	@Test
	void create_invalidType_paramMissing() {
		SysPermissionSaveReq req = menuReq("system:x");
		req.setPermType(9);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.createPermission(req));
		assertEquals(ErrorCode.PARAM_MISSING.getCode(), ex.getCode());
	}

	@Test
	void create_parentMissing_notFound() {
		when(permissionMapper.selectById(99L)).thenReturn(null);

		SysPermissionSaveReq req = menuReq("system:y");
		req.setParentId(99L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.createPermission(req));
		assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
	}

	@Test
	void update_cyclePrevented_conflict() {
		when(permissionMapper.selectById(1L)).thenReturn(perm(1L, 0L, "system"));
		when(permissionMapper.selectCount(any())).thenReturn(0L);
		SysPermission parent = perm(2L, 1L, "system:user");
		when(permissionMapper.selectById(2L)).thenReturn(parent);

		SysPermissionSaveReq req = menuReq("system");
		req.setParentId(2L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.updatePermission(1L, req));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
		verify(permissionMapper, never()).updateById(any(SysPermission.class));
	}

	@Test
	void delete_withChildren_conflict() {
		when(permissionMapper.selectById(1L)).thenReturn(perm(1L, 0L, "system"));
		when(permissionMapper.selectCount(any())).thenReturn(1L);

		BusinessException ex = assertThrows(BusinessException.class, () -> service.deletePermission(1L));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void delete_empty_cleansAssocsAndRefreshesAll() {
		when(permissionMapper.selectById(1L)).thenReturn(perm(1L, 0L, "system"));
		when(permissionMapper.selectCount(any())).thenReturn(0L);
		when(permissionMapper.deleteById(1L)).thenReturn(1);

		service.deletePermission(1L);

		verify(rolePermissionMapper).delete(any());
		verify(permissionMapper).deleteById(1L);
		verify(tokenService).refreshAllSessions(permissionResolver);
	}

	@Test
	void changeStatus_refreshesAll() {
		when(permissionMapper.selectById(1L)).thenReturn(perm(1L, 0L, "system"));

		service.changeStatus(1L, 1);

		ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
		verify(permissionMapper).updateById(captor.capture());
		assertEquals(1, captor.getValue().getStatus());
		verify(tokenService).refreshAllSessions(permissionResolver);
	}

}
