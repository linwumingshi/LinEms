package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分时电价管理。 */
@Service
public class EmsPriceService extends ServiceImpl<EmsElectricityPriceMapper, EmsElectricityPrice> {

    public Page<EmsElectricityPrice> page(long pageNo, long pageSize, Long stationId, String region) {
        return page(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EmsElectricityPrice>()
                        .eq(stationId != null, EmsElectricityPrice::getStationId, stationId)
                        .eq(region != null, EmsElectricityPrice::getRegion, region)
                        .orderByAsc(EmsElectricityPrice::getStartTime));
    }

    /** 批量保存：逐条补租户后插入。 */
    public void batchSave(List<EmsElectricityPrice> prices) {
        long tenant = requireTenant();
        for (EmsElectricityPrice p : prices) {
            p.setTenantId(tenant);
            if (p.getStatus() == null) p.setStatus(1);
            save(p);
        }
    }

    public void update(EmsElectricityPrice p) {
        p.setTenantId(null);
        updateById(p);
    }

    private long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        return t;
    }
}
