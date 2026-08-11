package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.EmsDemandRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 需量槽位记录读写（P1-2）。租户 id 由调用方显式传入（调度线程无 TenantContext）。 */
@Service
public class EmsDemandRecordService {

	private final EmsDemandRecordMapper mapper;

	public EmsDemandRecordService(EmsDemandRecordMapper mapper) {
		this.mapper = mapper;
	}

	/** 查某站某槽位记录；无返回 null。 */
	public EmsDemandRecord getByStationAndWindow(Long tenantId, Long stationId, LocalDateTime windowStart) {
		return mapper.selectOne(new LambdaQueryWrapper<EmsDemandRecord>().eq(EmsDemandRecord::getTenantId, tenantId)
			.eq(EmsDemandRecord::getStationId, stationId)
			.eq(EmsDemandRecord::getWindowStart, windowStart));
	}

	/** 按时间范围取槽位记录（windowStart 升序）。 */
	public List<EmsDemandRecord> listByRange(Long tenantId, Long stationId, LocalDateTime start, LocalDateTime end) {
		return mapper.selectList(new LambdaQueryWrapper<EmsDemandRecord>().eq(EmsDemandRecord::getTenantId, tenantId)
			.eq(EmsDemandRecord::getStationId, stationId)
			.ge(EmsDemandRecord::getWindowStart, start)
			.le(EmsDemandRecord::getWindowStart, end)
			.orderByAsc(EmsDemandRecord::getWindowStart));
	}

	/** 槽位记录 upsert（按 station_id+window_start 幂等）。返回入参。 */
	public EmsDemandRecord upsert(EmsDemandRecord rec) {
		mapper.upsert(rec);
		return rec;
	}

}
