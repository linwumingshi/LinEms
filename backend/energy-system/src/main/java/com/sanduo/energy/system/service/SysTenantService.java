package com.sanduo.energy.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.system.dto.SysTenantQuery;
import com.sanduo.energy.system.dto.SysTenantSaveReq;
import com.sanduo.energy.system.entity.SysTenant;

public interface SysTenantService extends IService<SysTenant> {

    PageResult<SysTenant> pageQuery(SysTenantQuery query);

    Long createTenant(SysTenantSaveReq req);

    void updateTenant(Long tenantId, SysTenantSaveReq req);

    void changeStatus(Long tenantId, Integer status);
}
