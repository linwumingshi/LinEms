package com.energyx.station.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.station.entity.Station;
import com.energyx.station.service.StationService;
import com.energyx.station.web.dto.StationQuery;
import com.energyx.station.web.dto.StationSaveReq;
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
 * <li>POST /station 创建电站；</li>
 * <li>GET /station/page 分页查询；</li>
 * <li>GET /station/{stationId} 电站详情；</li>
 * <li>PUT /station/{stationId} 更新电站；</li>
 * <li>DELETE /station/{stationId} 逻辑删除电站。</li>
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

	/**
	 * 创建电站。
	 * <p>
	 * 校验 {@link StationSaveReq} 后调用 {@link StationService#create} 持久化，返回新电站主键 ID。
	 * </p>
	 * @param req 请求体，字段说明见 {@link StationSaveReq}
	 * @return {@link Result}<{@link Long}> 新建电站的 stationId
	 */
	@PostMapping
	public Result<Long> create(@Valid @RequestBody StationSaveReq req) {
		return Result.ok(stationService.create(req));
	}

	/**
	 * 电站分页查询。
	 * <p>
	 * 按所属企业、名称/编码关键字、运行状态、电网类型筛选，返回分页结果。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link StationQuery}（来源：GET 查询参数）
	 * @return {@link Result}<{@link PageResult}<{@link Station}>> 分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<Station>> page(StationQuery query) {
		return Result.ok(PageResult.of(stationService.page(query)));
	}

	/**
	 * 电站详情。
	 * @param stationId 电站 ID（来源：路径变量）
	 * @return {@link Result}<{@link Station}> 电站详情
	 */
	@GetMapping("/{stationId}")
	public Result<Station> detail(@PathVariable Long stationId) {
		return Result.ok(stationService.detail(stationId));
	}

	/**
	 * 更新电站。
	 * <p>
	 * 按 stationId 定位并覆盖 {@link StationSaveReq} 中的可写字段。
	 * </p>
	 * @param stationId 电站 ID（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link StationSaveReq}
	 * @return {@link Result}<{@link Void}>
	 */
	@PutMapping("/{stationId}")
	public Result<Void> update(@PathVariable Long stationId, @Valid @RequestBody StationSaveReq req) {
		stationService.update(stationId, req);
		return Result.ok();
	}

	/**
	 * 逻辑删除电站。
	 * <p>
	 * 通过 {@link com.energyx.common.entity.BaseEntity} 的逻辑删除标记置为已删除，非物理删除。
	 * </p>
	 * @param stationId 电站 ID（来源：路径变量）
	 * @return {@link Result}<{@link Void}>
	 */
	@DeleteMapping("/{stationId}")
	public Result<Void> delete(@PathVariable Long stationId) {
		stationService.delete(stationId);
		return Result.ok();
	}

}
