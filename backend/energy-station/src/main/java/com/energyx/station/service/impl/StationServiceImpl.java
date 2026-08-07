package com.energyx.station.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.station.entity.Station;
import com.energyx.station.mapper.StationMapper;
import com.energyx.station.service.StationService;
import com.energyx.station.web.dto.StationQuery;
import com.energyx.station.web.dto.StationSaveReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 电站资产服务实现。
 *
 * <p>租户隔离由条件化租户拦截器自动完成（HTTP 线程按 {@link TenantContext} 追加 tenant_id），
 * 本服务仅在校验时读取当前租户写入。</p>
 */
@Slf4j
@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements StationService {

    @Override
    public Long create(StationSaveReq req) {
        long tenantId = requireTenant();

        long dup = count(new LambdaQueryWrapper<Station>()
                .eq(Station::getStationCode, req.getStationCode()));
        if (dup > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "电站编码已存在：" + req.getStationCode());
        }

        Station station = new Station();
        BeanUtils.copyProperties(req, station);
        station.setTenantId(tenantId);
        if (station.getStatus() == null) {
            station.setStatus(1);
        }
        save(station);
        log.info("创建电站 stationId={} code={} name={}", station.getStationId(),
                station.getStationCode(), station.getStationName());
        return station.getStationId();
    }

    @Override
    public void update(Long stationId, StationSaveReq req) {
        Station exists = requireStation(stationId);

        if (!Objects.equals(exists.getStationCode(), req.getStationCode())) {
            long dup = count(new LambdaQueryWrapper<Station>()
                    .eq(Station::getStationCode, req.getStationCode())
                    .ne(Station::getStationId, stationId));
            if (dup > 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "电站编码已存在：" + req.getStationCode());
            }
        }

        BeanUtils.copyProperties(req, exists);
        updateById(exists);
        log.info("更新电站 stationId={}", stationId);
    }

    @Override
    public void delete(Long stationId) {
        requireStation(stationId);
        removeById(stationId); // 逻辑删除
        log.info("删除电站 stationId={}", stationId);
    }

    @Override
    public IPage<Station> page(StationQuery query) {
        LambdaQueryWrapper<Station> wrapper = new LambdaQueryWrapper<Station>()
                .eq(query.getEnterpriseId() != null, Station::getEnterpriseId, query.getEnterpriseId())
                .eq(query.getStatus() != null, Station::getStatus, query.getStatus())
                .eq(query.getGridType() != null && !query.getGridType().isBlank(),
                        Station::getGridType, query.getGridType())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(Station::getStationName, query.getKeyword())
                        .or()
                        .like(Station::getStationCode, query.getKeyword()))
                .orderByDesc(Station::getCreateTime);
        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    @Override
    public Station detail(Long stationId) {
        return requireStation(stationId);
    }

    private Station requireStation(Long stationId) {
        Station station = getById(stationId);
        if (station == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "电站不存在：" + stationId);
        }
        return station;
    }

    private long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
        }
        return tenantId;
    }
}
