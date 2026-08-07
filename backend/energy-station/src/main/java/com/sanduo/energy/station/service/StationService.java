package com.sanduo.energy.station.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanduo.energy.station.entity.Station;
import com.sanduo.energy.station.web.dto.StationQuery;
import com.sanduo.energy.station.web.dto.StationSaveReq;

/**
 * 电站资产服务。
 */
public interface StationService {

    Long create(StationSaveReq req);

    void update(Long stationId, StationSaveReq req);

    void delete(Long stationId);

    IPage<Station> page(StationQuery query);

    Station detail(Long stationId);
}
