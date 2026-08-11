package com.energyx.ems.service;

import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.util.TdenginePlanWriter;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** EmsRevenueService 编排：summary/trend/detail/meta 组装、空态、ROI 年化。 */
class EmsRevenueServiceTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private EmsPlanMapper planMapper;

	private EmsElectricityPriceMapper priceMapper;

	private EmsStationMetaService stationMetaService;

	private PcsDeviceMapper pcsDeviceMapper;

	private TsdbClient tsdbClient;

	private TdenginePlanWriter writer;

	private EmsRevenueService svc;

	@BeforeEach
	void setUp() {
		TenantContext.set(new TenantInfo(7L, 100L));
		planMapper = mock(EmsPlanMapper.class);
		priceMapper = mock(EmsElectricityPriceMapper.class);
		stationMetaService = mock(EmsStationMetaService.class);
		pcsDeviceMapper = mock(PcsDeviceMapper.class);
		tsdbClient = mock(TsdbClient.class);
		writer = mock(TdenginePlanWriter.class);
		svc = new EmsRevenueService(planMapper, priceMapper, stationMetaService, pcsDeviceMapper, tsdbClient, writer);
		ReflectionTestUtils.setField(svc, "productKey", "snd_ess_pcs");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	@Test
	void summary_noDevicesReturnsZeros() {
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of());

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertEquals(0.0, s.getTotalEnergy(), 1e-9);
		assertEquals(0.0, s.getArbitrageRevenue(), 1e-9);
		assertEquals(1, s.getDaysCount());
		assertFalse(s.isHasInvestment());
	}

	@Test
	void summary_aggregatesTelemetryWithRunMode() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		when(priceMapper.selectList(any())).thenReturn(List.of());
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		// 00:00/00:10 放 60kW（各 10min=10kWh）、00:20/00:30 充 60kW（各 10min），无电价 → 收益 0
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List.of(
				new TsdbClient.TelemetryRow(start, 60.0, 2), new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2),
				new TsdbClient.TelemetryRow(start + 1_200_000L, 60.0, 1),
				new TsdbClient.TelemetryRow(start + 1_800_000L, 60.0, 1)));

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertEquals(20.0, s.getDischargeEnergy(), 1e-9);
		assertEquals(10.0, s.getChargeEnergy(), 1e-9);
		assertEquals(0.0, s.getArbitrageRevenue(), 1e-9);
	}

	@Test
	void summary_paybackFromInvestment() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		EmsElectricityPrice tier = new EmsElectricityPrice();
		tier.setPriceId(1L);
		tier.setTenantId(7L);
		tier.setStationId(10L);
		tier.setPriceType("PEAK");
		tier.setStartTime(java.time.LocalTime.of(0, 0));
		tier.setEndTime(java.time.LocalTime.of(0, 30));
		tier.setPrice(new BigDecimal("1.0"));
		tier.setValidFrom(DAY);
		tier.setValidTo(DAY);
		tier.setStatus(1);
		when(priceMapper.selectList(any())).thenReturn(List.of(tier));
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List
			.of(new TsdbClient.TelemetryRow(start, 60.0, 2), new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2))); // 10
																														// kWh
																														// 放电
																														// @1.0
																														// →
																														// 收益
																														// 10
		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("365000"));
		meta.setInstallDate(DAY);
		when(stationMetaService.getByStation(10L)).thenReturn(meta);

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertTrue(s.isHasInvestment());
		assertEquals(10.0, s.getArbitrageRevenue(), 1e-9);
		assertNotNull(s.getPaybackYears()); // 365000 ÷ (10×365) = 100 年
	}

	@Test
	void detail_returnsSlots() throws Exception {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setStationId(10L);
		plan.setPlanDate(DAY);
		plan.setPlanParam(
				"{\"priceDriven\":false,\"dischargeWindows\":[{\"start\":\"00:00\",\"end\":\"01:00\",\"powerLimit\":60}]}");
		when(planMapper.selectList(any())).thenReturn(List.of(plan));
		when(priceMapper.selectList(any())).thenReturn(List.of());
		when(writer.read(anyLong(), any()))
			.thenReturn(List.of(new com.energyx.ems.util.PlanPoint(java.time.LocalTime.of(0, 0), "DISCHARGE", 60, 80),
					new com.energyx.ems.util.PlanPoint(java.time.LocalTime.of(0, 10), "DISCHARGE", 60, 80)));
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any()))
			.thenReturn(List.of(new TsdbClient.TelemetryRow(start, 60.0, null),
					new TsdbClient.TelemetryRow(start + 600_000L, 60.0, null),
					new TsdbClient.TelemetryRow(start + 1_200_000L, 60.0, null))); // 无
																					// runMode
																					// →
																					// 回退计划
																					// DISCHARGE；第
																					// 3
																					// 行末点不计

		List<RevenueDetailRow> rows = svc.detail(10L, DAY);

		assertEquals(2, rows.size());
		assertEquals("DISCHARGE", rows.get(0).getAction());
		assertEquals("PLAN", rows.get(0).getSource());
	}

	@Test
	void trend_monthReturnsDailyPoints() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		when(priceMapper.selectList(any())).thenReturn(List.of());
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List
			.of(new TsdbClient.TelemetryRow(start, 60.0, 2), new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2)));

		List<RevenueTrendPoint> points = svc.trend(10L, "MONTH", DAY);

		assertEquals(31, points.size()); // 8 月 31 天
		assertEquals("08-01", points.get(0).getLabel());
		assertEquals(10.0, points.get(0).getDischargeEnergy(), 1e-9);
	}

}
