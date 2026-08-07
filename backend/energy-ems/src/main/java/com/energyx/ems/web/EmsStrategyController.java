package com.energyx.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsStrategy;
import com.energyx.ems.service.EmsStrategyService;
import com.energyx.ems.web.dto.EmsStrategySaveReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ems/strategy")
public class EmsStrategyController {

    private final EmsStrategyService service;

    public EmsStrategyController(EmsStrategyService service) {
        this.service = service;
    }

    @PostMapping
    public Result<EmsStrategy> create(@Valid @RequestBody EmsStrategySaveReq req) {
        return Result.ok(service.create(req.toEntity()));
    }

    @GetMapping("/page")
    public Result<PageResult<EmsStrategy>> page(@RequestParam(defaultValue = "1") long pageNo,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestParam(required = false) Long stationId,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) Integer status) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, type, status)));
    }

    @PutMapping("/{strategyId}")
    public Result<EmsStrategy> update(@PathVariable Long strategyId, @Valid @RequestBody EmsStrategySaveReq req) {
        req.setStrategyId(strategyId);
        return Result.ok(service.update(req.toEntity()));
    }

    @DeleteMapping("/{strategyId}")
    public Result<Void> delete(@PathVariable Long strategyId) {
        service.delete(strategyId);
        return Result.ok();
    }

    @PutMapping("/{strategyId}/status")
    public Result<Void> switchStatus(@PathVariable Long strategyId, @RequestParam int status) {
        service.switchStatus(strategyId, status);
        return Result.ok();
    }
}
