package com.energyx.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ems.entity.EmsDemandRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 需量槽位记录 Mapper。upsert 按 (station_id, window_start) 唯一键幂等。 */
@Mapper
public interface EmsDemandRecordMapper extends BaseMapper<EmsDemandRecord> {

	@Insert("""
			INSERT INTO ems_demand_record
			  (tenant_id, station_id, window_start, window_end, demand_kw, limit_kw, over_limit, shaved_kw, action)
			VALUES (#{tenantId}, #{stationId}, #{windowStart}, #{windowEnd}, #{demandKw}, #{limitKw}, #{overLimit}, #{shavedKw}, #{action})
			ON DUPLICATE KEY UPDATE
			  window_end = VALUES(window_end),
			  demand_kw = VALUES(demand_kw),
			  limit_kw = VALUES(limit_kw),
			  over_limit = VALUES(over_limit),
			  shaved_kw = VALUES(shaved_kw),
			  action = VALUES(action)
			""")
	int upsert(EmsDemandRecord rec);

}
