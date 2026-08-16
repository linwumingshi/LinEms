package com.energyx.tsdb.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import com.energyx.tsdb.service.TdengineQueryService;
import com.energyx.tsdb.sql.TdengineSqlBuilder;
import com.energyx.tsdb.web.dto.PropertyHistoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * TDengine 时序读接口。网关 /api/tsdb/** StripPrefix=1 → controller 映射 /tsdb（不带 /api）。
 */
@RestController
@RequestMapping("/tsdb")
public class TsdbController {

	private static final int MAX_IDENTIFIERS = 10;

	private static final long HOUR_MS = 3_600_000L;

	private static final long DAY_MS = 24L * HOUR_MS;

	private final TdengineQueryService queryService;

	public TsdbController(TdengineQueryService queryService) {
		this.queryService = queryService;
	}

	/**
	 * 查询设备属性的历史时序数据（TDengine 超级表）。按设备、产品、属性标识集合与时间窗分页返回， 时间范围默认最近一天，排序方向默认倒序。
	 * @param deviceId 设备 ID（来源：查询参数，必填）
	 * @param productKey 产品标识（来源：查询参数，必填，须为合法标识符）
	 * @param identifiers 属性标识列表，逗号分隔（来源：查询参数，必填，1~10 个）
	 * @param startTime 起始时间，epoch 毫秒（来源：查询参数，可选，默认一天前）
	 * @param endTime 结束时间，epoch 毫秒（来源：查询参数，可选，默认当前时间）
	 * @param order 排序方向：asc|desc（来源：查询参数，可选，默认 desc）
	 * @param page 页码，从 1 开始（来源：查询参数，可选，默认 1）
	 * @param size 每页大小（来源：查询参数，可选，默认 20，范围 1~1000）
	 * @return {@link Result}<{@link PropertyHistoryView}> 属性历史分页视图
	 * @throws Exception 参数非法（productKey/identifiers/分页/时间窗/order）或底层查询失败时抛出， 映射为业务异常
	 * {@link com.energyx.common.exception.ErrorCode#PARAM_INVALID}
	 */
	@GetMapping("/property/history")
	public Result<PropertyHistoryView> propertyHistory(@RequestParam("deviceId") String deviceId,
			@RequestParam("productKey") String productKey, @RequestParam("identifiers") String identifiers,
			@RequestParam(value = "startTime", required = false) Long startTime,
			@RequestParam(value = "endTime", required = false) Long endTime,
			@RequestParam(value = "order", defaultValue = "desc") String order,
			@RequestParam(value = "page", defaultValue = "1") Integer page,
			@RequestParam(value = "size", defaultValue = "20") Integer size) throws Exception {

		if (!TdengineSqlBuilder.isSafeKey(productKey)) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "productKey 非法");
		}
		List<String> ids = Arrays.stream(identifiers.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.distinct()
			.toList();
		if (ids.isEmpty() || ids.size() > MAX_IDENTIFIERS) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "identifiers 须为 1~10 个");
		}
		if (page == null || page < 1) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "page 须 ≥1");
		}
		if (size == null || size < 1 || size > 1000) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "size 须为 1~1000");
		}
		boolean asc;
		if ("asc".equalsIgnoreCase(order)) {
			asc = true;
		}
		else if ("desc".equalsIgnoreCase(order)) {
			asc = false;
		}
		else {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "order 仅支持 asc|desc");
		}

		long now = System.currentTimeMillis();
		long start = startTime != null ? startTime : now - DAY_MS;
		long end = endTime != null ? endTime : now;
		if (start >= end) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "时间范围非法：startTime 须早于 endTime");
		}

		try {
			PropertyHistoryView view = queryService.queryHistory(deviceId, productKey, ids, start, end, asc, page,
					size);
			return Result.ok(view);
		}
		catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
		}
	}

}
