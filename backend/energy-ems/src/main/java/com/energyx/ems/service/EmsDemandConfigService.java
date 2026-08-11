package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.mapper.EmsDemandConfigMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 需量配置读写（P1-2）。upsert 按 (tenant_id, station_id) 唯一。 */
@Service
public class EmsDemandConfigService extends ServiceImpl<EmsDemandConfigMapper, EmsDemandConfig> {

	/** 查站点需量配置；未配置返回 null。 */
	public EmsDemandConfig getByStation(Long stationId) {
		return getOne(new LambdaQueryWrapper<EmsDemandConfig>().eq(EmsDemandConfig::getTenantId, requireTenant())
			.eq(EmsDemandConfig::getStationId, stationId));
	}

	/** upsert 幂等：同站已存在则原位更新（保留主键），否则插入并补租户。 */
	public EmsDemandConfig upsert(EmsDemandConfig cfg) {
		cfg.setTenantId(requireTenant());
		EmsDemandConfig hit = getByStation(cfg.getStationId());
		if (hit != null) {
			cfg.setDemandConfigId(hit.getDemandConfigId());
			updateById(cfg);
			return cfg;
		}
		cfg.setDemandConfigId(null);
		save(cfg);
		return cfg;
	}

	/** 全量配置（调度线程无租户上下文，遍历全部租户的参与站）。 */
	public List<EmsDemandConfig> listAll() {
		return list();
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
