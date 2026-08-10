package com.energyx.system.service.impl;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.system.dto.SysEnterpriseSaveReq;
import com.energyx.system.entity.SysEnterprise;
import com.energyx.system.mapper.SysEnterpriseMapper;
import com.energyx.system.mapper.SysUserMapper;
import com.energyx.system.service.SysEnterpriseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单位管理服务单元测试：物化路径/层级计算、编码唯一、成环防护、删除约束。
 */
@ExtendWith(MockitoExtension.class)
class SysEnterpriseServiceImplTest {

	@Mock
	private SysEnterpriseMapper enterpriseMapper;

	@Mock
	private SysUserMapper userMapper;

	private SysEnterpriseService service;

	@BeforeEach
	void setUp() {
		SysEnterpriseServiceImpl impl = new SysEnterpriseServiceImpl(userMapper);
		ReflectionTestUtils.setField(impl, "baseMapper", enterpriseMapper);
		service = impl;
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private SysEnterpriseSaveReq req(String code) {
		SysEnterpriseSaveReq req = new SysEnterpriseSaveReq();
		req.setEnterpriseCode(code);
		req.setEnterpriseName("测试单位" + code);
		return req;
	}

	@Test
	void createRoot_computesRootPathAndLevel() {
		when(enterpriseMapper.selectCount(any())).thenReturn(0L);
		when(enterpriseMapper.insert(any(SysEnterprise.class))).thenAnswer(inv -> {
			// 模拟 MyBatis-Plus 插入后回填自增主键
			inv.getArgument(0, SysEnterprise.class).setEnterpriseId(1L);
			return 1;
		});

		Long id = service.createEnterprise(req("ROOT"));

		// 路径回填发生在第二次 updateById
		ArgumentCaptor<SysEnterprise> captor = ArgumentCaptor.forClass(SysEnterprise.class);
		verify(enterpriseMapper).updateById(captor.capture());
		SysEnterprise saved = captor.getValue();
		assertEquals("/1/", saved.getPath());
		assertEquals(1, saved.getLevel());
		assertEquals(0L, saved.getParentId());
		assertEquals(1L, saved.getTenantId());
		assertEquals(1, saved.getStatus());
		assertEquals(0, saved.getSort());
		assertEquals(id, saved.getEnterpriseId());
	}

	@Test
	void createChild_computesPathFromParent() {
		when(enterpriseMapper.selectCount(any())).thenReturn(0L);
		when(enterpriseMapper.selectById(1L)).thenReturn(parent(1L, "/1/", 1));
		when(enterpriseMapper.insert(any(SysEnterprise.class))).thenAnswer(inv -> {
			// 模拟 MyBatis-Plus 插入后回填自增主键
			inv.getArgument(0, SysEnterprise.class).setEnterpriseId(2L);
			return 1;
		});

		SysEnterpriseSaveReq req = req("CHILD");
		req.setParentId(1L);
		service.createEnterprise(req);

		ArgumentCaptor<SysEnterprise> captor = ArgumentCaptor.forClass(SysEnterprise.class);
		verify(enterpriseMapper).updateById(captor.capture());
		assertEquals("/1/2/", captor.getValue().getPath());
		assertEquals(2, captor.getValue().getLevel());
	}

	@Test
	void create_duplicateCode_conflict() {
		when(enterpriseMapper.selectCount(any())).thenReturn(1L);

		BusinessException ex = assertThrows(BusinessException.class, () -> service.createEnterprise(req("DUP")));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void create_parentMissing_notFound() {
		when(enterpriseMapper.selectCount(any())).thenReturn(0L);
		when(enterpriseMapper.selectById(99L)).thenReturn(null);

		SysEnterpriseSaveReq req = req("X");
		req.setParentId(99L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.createEnterprise(req));
		assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
	}

	@Test
	void update_selfAsParent_conflict() {
		when(enterpriseMapper.selectById(1L)).thenReturn(parent(1L, "/1/", 1));

		SysEnterpriseSaveReq req = req("SELF");
		req.setParentId(1L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.updateEnterprise(1L, req));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void update_parentInOwnSubtree_conflict() {
		SysEnterprise root = parent(1L, "/1/", 1);
		when(enterpriseMapper.selectById(1L)).thenReturn(root);
		when(enterpriseMapper.selectById(3L)).thenReturn(parent(3L, "/1/3/", 2));

		SysEnterpriseSaveReq req = req("CYCLE");
		req.setParentId(3L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.updateEnterprise(1L, req));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void delete_withChildren_conflict() {
		when(enterpriseMapper.selectById(1L)).thenReturn(parent(1L, "/1/", 1));
		when(enterpriseMapper.selectCount(any())).thenReturn(1L);

		BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteEnterprise(1L));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void delete_withUsers_conflict() {
		when(enterpriseMapper.selectById(1L)).thenReturn(parent(1L, "/1/", 1));
		when(enterpriseMapper.selectCount(any())).thenReturn(0L);
		when(userMapper.selectCount(any())).thenReturn(1L);

		BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteEnterprise(1L));
		assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
	}

	@Test
	void delete_empty_removes() {
		when(enterpriseMapper.selectById(1L)).thenReturn(parent(1L, "/1/", 1));
		when(enterpriseMapper.selectCount(any())).thenReturn(0L);
		when(userMapper.selectCount(any())).thenReturn(0L);
		when(enterpriseMapper.deleteById(1L)).thenReturn(1);

		service.deleteEnterprise(1L);
		verify(enterpriseMapper).deleteById(1L);
	}

	@Test
	void tree_buildsHierarchy() {
		SysEnterprise root = parent(1L, "/1/", 1);
		SysEnterprise child = parent(2L, "/1/2/", 2);
		SysEnterprise grandchild = parent(3L, "/1/2/3/", 3);
		grandchild.setParentId(2L);
		when(enterpriseMapper.selectList(any())).thenReturn(List.of(root, child, grandchild));

		List<SysEnterprise> tree = service.tree();

		assertEquals(1, tree.size());
		assertEquals(1L, tree.get(0).getEnterpriseId());
		assertNotNull(tree.get(0).getChildren());
		assertEquals(1, tree.get(0).getChildren().size());
		assertEquals(2L, tree.get(0).getChildren().get(0).getEnterpriseId());
		assertEquals(1, tree.get(0).getChildren().get(0).getChildren().size());
	}

	@Test
	void update_moveToAnotherParent_cascadesChildren() {
		SysEnterprise root = parent(1L, "/1/", 1);
		when(enterpriseMapper.selectById(1L)).thenReturn(root);
		// 新父节点 2（根路径 /1/ 之外的独立子树 /2/）
		when(enterpriseMapper.selectById(2L)).thenReturn(parent(2L, "/2/", 1));

		SysEnterprise child = parent(3L, "/1/3/", 2);
		when(enterpriseMapper.selectList(any())).thenReturn(List.of(child));

		SysEnterpriseSaveReq req = req("MOVED");
		req.setParentId(2L);
		service.updateEnterprise(1L, req);

		// 自身路径更新 + 子节点级联修正（两次 updateById，按调用顺序断言）
		ArgumentCaptor<SysEnterprise> captor = ArgumentCaptor.forClass(SysEnterprise.class);
		verify(enterpriseMapper, times(2)).updateById(captor.capture());
		List<SysEnterprise> updates = captor.getAllValues();
		assertEquals("/2/1/", updates.get(0).getPath());
		assertEquals(2, updates.get(0).getLevel());
		assertEquals("/2/1/3/", updates.get(1).getPath());
		assertEquals(3, updates.get(1).getLevel());
	}

	@Test
	void update_parentMissing_notFound() {
		SysEnterprise root = parent(1L, "/1/", 1);
		when(enterpriseMapper.selectById(1L)).thenReturn(root);
		when(enterpriseMapper.selectById(99L)).thenReturn(null);

		SysEnterpriseSaveReq req = req("X");
		req.setParentId(99L);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.updateEnterprise(1L, req));
		assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
	}

	@Test
	void changeStatus_notFound() {
		when(enterpriseMapper.selectById(1L)).thenReturn(null);
		BusinessException ex = assertThrows(BusinessException.class, () -> service.changeStatus(1L, 0));
		assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
	}

	private SysEnterprise parent(Long id, String path, int level) {
		SysEnterprise e = new SysEnterprise();
		e.setEnterpriseId(id);
		e.setTenantId(1L);
		e.setParentId(id == 1L ? 0L : 1L);
		e.setPath(path);
		e.setLevel(level);
		e.setEnterpriseCode("CODE" + id);
		e.setEnterpriseName("单位" + id);
		e.setStatus(1);
		return e;
	}

}
