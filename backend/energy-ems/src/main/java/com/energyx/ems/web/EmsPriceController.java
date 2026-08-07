package com.energyx.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.service.EmsPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/ems/price")
public class EmsPriceController {

    private final EmsPriceService service;

    public EmsPriceController(EmsPriceService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public Result<PageResult<EmsElectricityPrice>> page(@RequestParam(defaultValue = "1") long pageNo,
                                                        @RequestParam(defaultValue = "10") long pageSize,
                                                        @RequestParam(required = false) Long stationId,
                                                        @RequestParam(required = false) String region) {
        return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, region)));
    }

    /** 批量保存分时电价。 */
    @PostMapping
    public Result<Void> batchSave(@RequestBody List<EmsElectricityPrice> prices) {
        service.batchSave(prices);
        return Result.ok();
    }

    @PutMapping("/{priceId}")
    public Result<Void> update(@PathVariable Long priceId, @RequestBody EmsElectricityPrice price) {
        price.setPriceId(priceId);
        service.update(price);
        return Result.ok();
    }
}
