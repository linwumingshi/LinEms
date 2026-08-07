package com.sanduo.energy.station.web;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.station.entity.Station;
import com.sanduo.energy.station.service.StationService;
import com.sanduo.energy.station.web.dto.StationQuery;
import com.sanduo.energy.station.web.dto.StationSaveReq;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电站资产 API。
 *
 * <ul>
 *   <li>POST   /api/station       创建电站；</li>
 *   <li>GET    /api/station/page  分页查询；</li>
 *   <li>GET    /api/station/{id}  详情；</li>
 *   <li>PUT    /api/station/{id}  更新；</li>
 *   <li>DELETE /api/station/{id}  逻辑删除。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/station")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody StationSaveReq req) {
        return Result.ok(stationService.create(req));
    }

    @GetMapping("/page")
    public Result<PageResult<Station>> page(StationQuery query) {
        return Result.ok(PageResult.of(stationService.page(query)));
    }

    @GetMapping("/{stationId}")
    public Result<Station> detail(@PathVariable Long stationId) {
        return Result.ok(stationService.detail(stationId));
    }

    @PutMapping("/{stationId}")
    public Result<Void> update(@PathVariable Long stationId, @Valid @RequestBody StationSaveReq req) {
        stationService.update(stationId, req);
        return Result.ok();
    }

    @DeleteMapping("/{stationId}")
    public Result<Void> delete(@PathVariable Long stationId) {
        stationService.delete(stationId);
        return Result.ok();
    }
}
