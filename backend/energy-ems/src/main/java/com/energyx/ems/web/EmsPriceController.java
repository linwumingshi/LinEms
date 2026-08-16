package com.energyx.ems.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.service.EmsPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分时电价档案接口。网关 /api/ems/** → energy-ems，控制器映射带 /ems。
 * <ul>
 * <li>GET /ems/price/page — 分页查询分时电价</li>
 * <li>POST /ems/price — 批量保存分时电价</li>
 * <li>PUT /ems/price/{priceId} — 更新单条电价</li>
 * <li>DELETE /ems/price/{priceId} — 删除单条电价</li>
 * </ul>
 */
@RestController
@RequestMapping("/ems/price")
public class EmsPriceController {

	private final EmsPriceService service;

	public EmsPriceController(EmsPriceService service) {
		this.service = service;
	}

	/**
	 * 分页查询分时电价。支持按站点、区域筛选。
	 * @param pageNo 页码（来源：查询参数，默认 1）
	 * @param pageSize 每页条数（来源：查询参数，默认 10）
	 * @param stationId 站点 ID（来源：查询参数，可选）
	 * @param region 区域编码（来源：查询参数，可选）
	 * @return {@link Result}<{@link PageResult}<{@link EmsElectricityPrice}>> 分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<EmsElectricityPrice>> page(@RequestParam(defaultValue = "1") long pageNo,
			@RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) Long stationId,
			@RequestParam(required = false) String region) {
		return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, region)));
	}

	/**
	 * 批量保存分时电价。逐条写入电价档案（含档位、时段、价格、有效期）。
	 * @param prices 电价档案列表，元素字段说明见 {@link EmsElectricityPrice}
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@PostMapping
	public Result<Void> batchSave(@RequestBody List<EmsElectricityPrice> prices) {
		service.batchSave(prices);
		return Result.ok();
	}

	/**
	 * 更新单条分时电价。将路径中的 priceId 写入实体后持久化。
	 * @param priceId 电价 ID（来源：路径变量）
	 * @param price 电价实体，字段说明见 {@link EmsElectricityPrice}
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@PutMapping("/{priceId}")
	public Result<Void> update(@PathVariable Long priceId, @RequestBody EmsElectricityPrice price) {
		price.setPriceId(priceId);
		service.update(price);
		return Result.ok();
	}

	/**
	 * 删除单条分时电价。
	 * @param priceId 电价 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}> 操作结果
	 */
	@DeleteMapping("/{priceId}")
	public Result<Void> delete(@PathVariable Long priceId) {
		service.delete(priceId);
		return Result.ok();
	}

}
