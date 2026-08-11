package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsStationMetaMapper;
import org.springframework.stereotype.Service;

/** 电站投资元数据读写（P1-1 收益核算 ROI 数据源）。upsert 按 station_id 唯一。 */
@Service
public class EmsStationMetaService extends ServiceImpl<EmsStationMetaMapper, EmsStationMeta> {

	/** 查电站投资元数据；未配置返回 null。 */
	public EmsStationMeta getByStation(Long stationId) {
		return getOne(new LambdaQueryWrapper<EmsStationMeta>().eq(EmsStationMeta::getTenantId, requireTenant())
			.eq(EmsStationMeta::getStationId, stationId));
	}

	/** upsert 幂等：同站已存在则原位更新（保留主键），否则插入并补租户。 */
	public EmsStationMeta upsert(EmsStationMeta meta) {
		meta.setTenantId(requireTenant());
		EmsStationMeta hit = getByStation(meta.getStationId());
		if (hit != null) {
			meta.setStationMetaId(hit.getStationMetaId());
			updateById(meta);
			return meta;
		}
		meta.setStationMetaId(null);
		save(meta);
		return meta;
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
