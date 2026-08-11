package com.energyx.ems.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * energy-tsdb 时序读客户端（P1-1 收益核算）。按 Nacos 服务名解析，不写死 URL（跨服务 Feign 契约）。 网关 /api/tsdb/**
 * StripPrefix=1 → tsdb 控制器映射 /tsdb，Feign path 用 /tsdb。
 */
@FeignClient(name = "energy-tsdb", path = "/tsdb")
public interface TsdbFeignClient {

	/** 属性历史查询（identifiers 逗号分隔如 "power,runMode"，size ≤1000；参数全部显式传入） */
	@GetMapping("/property/history")
	Result<TsdbHistoryViewDto> history(@RequestParam("deviceId") String deviceId,
			@RequestParam("productKey") String productKey, @RequestParam("identifiers") String identifiers,
			@RequestParam("startTime") long startTime, @RequestParam("endTime") long endTime,
			@RequestParam("order") String order, @RequestParam("page") int page, @RequestParam("size") int size);

}
