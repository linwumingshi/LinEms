package com.energyx.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.energyx.system.dto.SysEnterpriseSaveReq;
import com.energyx.system.entity.SysEnterprise;

import java.util.List;

/**
 * 单位管理服务（组织树 CRUD）。
 */
public interface SysEnterpriseService extends IService<SysEnterprise> {

    /** 全量组织树（按 sort 升序，供管理页渲染）。 */
    List<SysEnterprise> tree();

    /** 全量扁平列表（含已建树，供父级下拉选择）。 */
    List<SysEnterprise> listAll();

    /** 创建单位：编码唯一校验 + 物化路径/层级计算。 */
    Long createEnterprise(SysEnterpriseSaveReq req);

    /** 更新单位：编码唯一、防止成环、路径变更级联修正子树。 */
    void updateEnterprise(Long enterpriseId, SysEnterpriseSaveReq req);

    /** 删除单位：存在子节点或关联用户则拒绝。 */
    void deleteEnterprise(Long enterpriseId);

    /** 变更状态：0 禁用 1 启用。 */
    void changeStatus(Long enterpriseId, Integer status);
}
