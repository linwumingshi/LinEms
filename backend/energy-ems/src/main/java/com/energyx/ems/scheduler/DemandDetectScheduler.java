package com.energyx.ems.scheduler;

import com.energyx.common.redis.DistributedLock;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.MeterDeviceMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.service.DemandAlarmProducer;
import com.energyx.ems.service.DemandShaveClient;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.TsdbClient;
import com.energyx.ems.util.DemandDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 需量检测调度器（P1-2）：每分钟遍历有需量配置的站，检测当前 15min 槽位，超限即削峰下发 + 留痕 + 告警。
 *
 * <p>
 * 调度线程无租户上下文（TenantContext 仅 HTTP 线程生效），租户 id 一律取配置行；单站失败仅 log 不中断循环。
 * </p>
 */
@Slf4j
@Component
public class DemandDetectScheduler {

	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	private final MeterDeviceMapper meterDeviceMapper;

	private final PcsDeviceMapper pcsDeviceMapper;

	private final TsdbClient tsdbClient;

	private final DemandShaveClient shaveClient;

	private final DemandAlarmProducer alarmProducer;

	private final DistributedLock distributedLock;

	@Value("${energyx.ems.meter-product-key:snd_ess_meter}")
	private String meterProductKey;

	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String pcsProductKey;

	public DemandDetectScheduler(EmsDemandConfigService configService, EmsDemandRecordService recordService,
			MeterDeviceMapper meterDeviceMapper, PcsDeviceMapper pcsDeviceMapper, TsdbClient tsdbClient,
			DemandShaveClient shaveClient, DemandAlarmProducer alarmProducer, DistributedLock distributedLock) {
		this.configService = configService;
		this.recordService = recordService;
		this.meterDeviceMapper = meterDeviceMapper;
		this.pcsDeviceMapper = pcsDeviceMapper;
		this.tsdbClient = tsdbClient;
		this.shaveClient = shaveClient;
		this.alarmProducer = alarmProducer;
		this.distributedLock = distributedLock;
	}

	/** 每分钟触发：全量配置站检测。锁 TTL 60s 覆盖最坏遍历耗时。 */
	@Scheduled(cron = "0 * * * * *")
	public void detect() {
		distributedLock.runIfAcquired("scheduled:ems-demand-detect", 60, this::doDetect);
	}

	private void doDetect() {
		LocalDate today = LocalDate.now();
		for (EmsDemandConfig config : configService.listAll()) {
			try {
				detectOne(config, today);
			}
			catch (Exception e) {
				log.warn("[DemandDetect] 站点检测失败 stationId={} msg={}", config.getStationId(), e.getMessage());
			}
		}
	}

	private void detectOne(EmsDemandConfig config, LocalDate today) {
		if (config.getDemandLimitKw() == null || config.getDemandLimitKw().signum() <= 0) {
			log.warn("[DemandDetect] 站点未配置需量限值，跳过 stationId={}", config.getStationId());
			return;
		}
		List<MeterDevice> meters = meterDeviceMapper.selectByStation(config.getTenantId(), config.getStationId(),
				meterProductKey);
		if (meters == null || meters.isEmpty()) {
			log.warn("[DemandDetect] 站点无电表，跳过 stationId={}", config.getStationId());
			return;
		}
		MeterDevice meter = meters.get(0);
		List<TsdbClient.TelemetryRow> rows = tsdbClient.history(meter.deviceId(), meter.productKey(), today);
		if (rows == null || rows.isEmpty()) {
			log.warn("[DemandDetect] 电表无遥测，跳过 stationId={} deviceId={}", config.getStationId(), meter.deviceId());
			return;
		}
		List<PcsDevice> pcs = pcsDeviceMapper.selectByStation(config.getTenantId(), config.getStationId(),
				pcsProductKey);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime slotStart = DemandDetector.slotStart(now);
		LocalDateTime slotEnd = DemandDetector.slotEnd(slotStart);
		double avg = DemandDetector.slotAvg(rows, slotStart);
		double limit = num(config.getDemandLimitKw());
		DemandDetector.DetectResult result = DemandDetector.detect(avg, limit, pcs == null ? 0 : pcs.size());

		EmsDemandRecord existing = recordService.getByStationAndWindow(config.getTenantId(), config.getStationId(),
				slotStart);
		boolean wasOver = existing != null && Boolean.TRUE.equals(existing.getOverLimit());
		if (result.overLimit()) {
			shaveClient.shave(config, pcs, slotStart, slotEnd, result.demandKw(), result.limitKw(), result.shaveKw());
			if (!wasOver) { // 首超才告警（幂等）
				alarmProducer.publishDemandOverLimit(config, meter, result.demandKw(), result.limitKw(), slotStart);
			}
		}
		else if (!wasOver) {
			// 未超限且槽位尚未定型超限 → 写 NONE（demand_kw 随运行均值定型）；已超限则保留，避免 NONE 覆盖削峰痕迹
			recordService.upsert(newRecord(config, slotStart, slotEnd, avg, limit, false, 0, "NONE"));
		}
	}

	private static EmsDemandRecord newRecord(EmsDemandConfig config, LocalDateTime slotStart, LocalDateTime slotEnd,
			double demandKw, double limitKw, boolean overLimit, double shaveKw, String action) {
		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(config.getTenantId());
		rec.setStationId(config.getStationId());
		rec.setWindowStart(slotStart);
		rec.setWindowEnd(slotEnd);
		rec.setDemandKw(BigDecimal.valueOf(round2(demandKw)));
		rec.setLimitKw(BigDecimal.valueOf(round2(limitKw)));
		rec.setOverLimit(overLimit);
		rec.setShavedKw(BigDecimal.valueOf(round2(shaveKw)));
		rec.setAction(action);
		return rec;
	}

	private static double num(BigDecimal v) {
		return v == null ? 0 : v.doubleValue();
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

}
