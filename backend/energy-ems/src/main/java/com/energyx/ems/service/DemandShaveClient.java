package com.energyx.ems.service;

import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.model.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需量削峰下发封装（P1-2）：向站内活跃 PCS 均分削峰功率下 DISCHARGE，并按槽位留痕。
 *
 * <p>
 * 无活跃 PCS → 仅记录 ALARM_ONLY（不削峰）；任一 PCS 下发失败 → action=SHED_FAILED（不中断，shaved_kw 保留意图值）。
 * </p>
 */
@Slf4j
@Component
public class DemandShaveClient {

	private final CommandClient commandClient;

	private final ShadowClient shadowClient;

	private final EmsDemandRecordService recordService;

	public DemandShaveClient(CommandClient commandClient, ShadowClient shadowClient,
			EmsDemandRecordService recordService) {
		this.commandClient = commandClient;
		this.shadowClient = shadowClient;
		this.recordService = recordService;
	}

	/**
	 * 削峰下发 + 槽位留痕（upsert 幂等）。
	 * @param devices 站内活跃 PCS（调度器已解析，避免二次查询）
	 * @param shaveKw 削峰功率（DemandDetector 已按可用功率钳制）
	 */
	public EmsDemandRecord shave(EmsDemandConfig config, List<DeviceInfo> devices, LocalDateTime windowStart,
			LocalDateTime windowEnd, double demandKw, double limitKw, double shaveKw) {
		if (devices == null || devices.isEmpty()) {
			return recordService
				.upsert(newRecord(config, windowStart, windowEnd, demandKw, limitKw, true, 0, "ALARM_ONLY"));
		}
		double share = round2(shaveKw / devices.size());
		boolean anyFailed = false;
		for (DeviceInfo dev : devices) {
			try {
				dispatchShave(dev, share, windowStart);
			}
			catch (Exception e) {
				log.warn("[DemandShave] 削峰下发失败 deviceId={} msg={}", dev.deviceId(), e.getMessage());
				anyFailed = true;
			}
		}
		String action = anyFailed ? "SHED_FAILED" : "SHED";
		return recordService
			.upsert(newRecord(config, windowStart, windowEnd, demandKw, limitKw, true, shaveKw, action));
	}

	private void dispatchShave(DeviceInfo dev, double share, LocalDateTime windowStart) {
		Map<String, Object> params = new HashMap<>();
		params.put("action", "DISCHARGE");
		params.put("power", share);
		params.put("socTarget", socTargetOf(dev));
		params.put("time", windowStart.toString());
		commandClient.dispatch(dev.productKey(), dev.deviceName(), "DISCHARGE", params, 0L);
	}

	/** 放停下限：影子实时 SOC 兜底，无则回退 30（防深放）。 */
	private double socTargetOf(DeviceInfo dev) {
		return shadowClient.reportedSoc(dev.deviceId()).orElse(30.0);
	}

	private static EmsDemandRecord newRecord(EmsDemandConfig config, LocalDateTime windowStart, LocalDateTime windowEnd,
			double demandKw, double limitKw, boolean overLimit, double shaveKw, String action) {
		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(config.getTenantId());
		rec.setStationId(config.getStationId());
		rec.setWindowStart(windowStart);
		rec.setWindowEnd(windowEnd);
		rec.setDemandKw(BigDecimal.valueOf(round2(demandKw)));
		rec.setLimitKw(BigDecimal.valueOf(round2(limitKw)));
		rec.setOverLimit(overLimit);
		rec.setShavedKw(BigDecimal.valueOf(round2(shaveKw)));
		rec.setAction(action);
		return rec;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

}
