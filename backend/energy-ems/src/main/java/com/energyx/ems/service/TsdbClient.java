package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.TsdbFeignClient;
import com.energyx.ems.client.TsdbHistoryRecordDto;
import com.energyx.ems.client.TsdbHistoryViewDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备遥测时序读取包装（P1-1 收益核算）。底层走 Feign（Nacos 服务名 energy-tsdb 解析，无硬编码 URL）。 一次查询取 power+runMode
 * 两个属性；分页循环拉满当天；任一步失败返回空列表（单设备查询失败不影响电站整体核算）。
 */
@Slf4j
@Component
public class TsdbClient {

	/** 单页上限，与 tsdb 服务端一致 */
	private static final int PAGE_SIZE = 1000;

	private final TsdbFeignClient feignClient;

	public TsdbClient(TsdbFeignClient feignClient) {
		this.feignClient = feignClient;
	}

	/** 遥测采样行：ts(epoch 毫秒)、power(kW，可空)、runMode(1充/2放，可空) */
	public record TelemetryRow(long ts, Double power, Integer runMode) {
	}

	/** 拉取设备某日 power+runMode 遥测（按 ts 升序）。分页循环直到拉满 total；失败返回空列表并告警。 */
	public List<TelemetryRow> history(long deviceId, String productKey, LocalDate date) {
		try {
			long start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			long end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			List<TelemetryRow> out = new ArrayList<>();
			int page = 1;
			long total = Long.MAX_VALUE;
			while (out.size() < total) {
				Result<TsdbHistoryViewDto> result = feignClient.history(String.valueOf(deviceId), productKey,
						"power,runMode", start, end, "asc", page, PAGE_SIZE);
				if (result == null || !result.isSuccess()) {
					log.warn("TSDB 查询失败 deviceId={} date={} code={} msg={}", deviceId, date,
							result == null ? -1 : result.getCode(), result == null ? "null" : result.getMessage());
					break;
				}
				TsdbHistoryViewDto view = result.getData();
				if (view == null || view.getRecords() == null || view.getRecords().isEmpty()) {
					break;
				}
				total = view.getTotal();
				for (TsdbHistoryRecordDto rec : view.getRecords()) {
					Double power = number(rec.getValues(), "power");
					Double rm = number(rec.getValues(), "runMode");
					out.add(new TelemetryRow(rec.getTs(), power, rm == null ? null : rm.intValue()));
				}
				if (view.getRecords().size() < PAGE_SIZE) {
					break;
				}
				page++;
			}
			out.sort((a, b) -> Long.compare(a.ts(), b.ts()));
			return out;
		}
		catch (Exception e) {
			log.warn("拉取遥测失败 deviceId={} date={}: {}", deviceId, date, e.getMessage());
			return List.of();
		}
	}

	private static Double number(Map<String, Object> values, String key) {
		Object v = values.get(key);
		return v instanceof Number n ? n.doubleValue() : null;
	}

}
