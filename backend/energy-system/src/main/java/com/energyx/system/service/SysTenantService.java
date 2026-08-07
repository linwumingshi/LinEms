package com.energyx.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.energyx.common.model.PageResult;
import com.energyx.system.dto.SysTenantQuery;
import com.energyx.system.dto.SysTenantSaveReq;
import com.energyx.system.entity.SysTenant;

public interface SysTenantService extends IService<SysTenant> {

    PageResult<SysTenant> pageQuery(SysTenantQuery query);

    Long createTenant(SysTenantSaveReq req);

    void updateTenant(Long tenantId, SysTenantSaveReq req);

    void changeStatus(Long tenantId, Integer status);
}
