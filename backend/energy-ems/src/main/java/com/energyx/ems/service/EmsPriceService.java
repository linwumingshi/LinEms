package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.enums.ElectricityPriceStatus;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 分时电价管理。 */
@Service
public class EmsPriceService extends ServiceImpl<EmsElectricityPriceMapper, EmsElectricityPrice> {

	public Page<EmsElectricityPrice> page(long pageNo, long pageSize, Long stationId, String region) {
		return page(new Page<>(pageNo, pageSize),
				new LambdaQueryWrapper<EmsElectricityPrice>()
					.eq(stationId != null, EmsElectricityPrice::getStationId, stationId)
					.eq(region != null, EmsElectricityPrice::getRegion, region)
					.orderByAsc(EmsElectricityPrice::getStartTime));
	}

	/**
	 * 批量保存（upsert 幂等）：同站同 startTime 视为同一档位——已存在则原位更新（保留 priceId/create_time），否则插入。
	 * 重复提交同一批档位不产生重复行（与 PlanGenerator 按 startTime 去重语义一致）。
	 */
	public void batchSave(List<EmsElectricityPrice> prices) {
		if (prices == null || prices.isEmpty())
			return;
		long tenant = requireTenant();
		// 一次查出本批涉及电站的现有档位，按 (stationId, startTime) 建索引，避免逐条 selectOne
		Map<String, EmsElectricityPrice> existing = new HashMap<>();
		List<Long> stationIds = prices.stream()
			.map(EmsElectricityPrice::getStationId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (!stationIds.isEmpty()) {
			for (EmsElectricityPrice row : list(
					new LambdaQueryWrapper<EmsElectricityPrice>().eq(EmsElectricityPrice::getTenantId, tenant)
						.in(EmsElectricityPrice::getStationId, stationIds))) {
				existing.put(upsertKey(row.getStationId(), row.getStartTime()), row);
			}
		}
		for (EmsElectricityPrice p : prices) {
			p.setTenantId(tenant);
			if (p.getStatus() == null)
				p.setStatus(ElectricityPriceStatus.ENABLED);
			EmsElectricityPrice hit = existing.get(upsertKey(p.getStationId(), p.getStartTime()));
			if (hit != null) {
				p.setPriceId(hit.getPriceId()); // 原位更新，保留主键与 create_time
				updateById(p);
			}
			else {
				p.setPriceId(null);
				save(p);
			}
		}
	}

	public void update(EmsElectricityPrice p) {
		p.setTenantId(null);
		updateById(p);
	}

	/** 删除档位；不存在抛 NOT_FOUND。 */
	public void delete(Long priceId) {
		if (!removeById(priceId))
			throw new BusinessException(ErrorCode.NOT_FOUND, "电价档位不存在: " + priceId);
	}

	private static String upsertKey(Long stationId, LocalTime startTime) {
		return stationId + ":" + startTime;
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null)
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		return t;
	}

}
