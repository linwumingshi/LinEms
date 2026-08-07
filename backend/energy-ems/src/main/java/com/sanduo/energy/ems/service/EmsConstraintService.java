package com.sanduo.energy.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sanduo.energy.common.exception.BusinessException;
import com.sanduo.energy.common.exception.ErrorCode;
import com.sanduo.energy.common.tenant.TenantContext;
import com.sanduo.energy.ems.entity.EmsConstraint;
import com.sanduo.energy.ems.mapper.EmsConstraintMapper;
import org.springframework.stereotype.Service;

/** 安全约束管理（一电站一条，uk_constraint_station）。 */
@Service
public class EmsConstraintService extends ServiceImpl<EmsConstraintMapper, EmsConstraint> {

    public EmsConstraint getByStation(Long stationId) {
        return getOne(new LambdaQueryWrapper<EmsConstraint>()
                .eq(EmsConstraint::getStationId, stationId));
    }

    /** 保存/更新安全约束（一电站一条 upsert）。 */
    public EmsConstraint saveConstraint(EmsConstraint c) {
        long tenant = requireTenant();
        EmsConstraint exists = getByStation(c.getStationId());
        c.setTenantId(tenant);
        if (exists == null) {
            if (c.getStatus() == null) c.setStatus(1);
            save(c);
        } else {
            c.setConstraintId(exists.getConstraintId());
            c.setTenantId(null); // 租户不可改
            updateById(c);
        }
        return c;
    }

    private long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        return t;
    }
}
