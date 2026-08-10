package com.energyx.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ems.entity.EmsExecutionRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalTime;
import java.util.List;

public interface EmsExecutionRecordMapper extends BaseMapper<EmsExecutionRecord> {

	/** 按计划 + 计划点时刻查执行记录（调度器到点下发查重锚点，配合 uk_exec_plan_time） */
	@Select("SELECT * FROM ems_execution_record WHERE plan_id = #{planId} AND plan_time = #{planTime}")
	EmsExecutionRecord selectByPlanAndTime(@Param("planId") Long planId, @Param("planTime") LocalTime planTime);

	/** 按指令 ID 查执行记录（ACK 回写定位） */
	@Select("SELECT * FROM ems_execution_record WHERE command_id = #{commandId}")
	EmsExecutionRecord selectByCommandId(@Param("commandId") String commandId);

	/** 某计划全部执行记录（按计划点时刻升序，供前端查看执行进度） */
	@Select("SELECT * FROM ems_execution_record WHERE plan_id = #{planId} ORDER BY plan_time")
	List<EmsExecutionRecord> selectByPlanId(@Param("planId") Long planId);

	/** 回写执行结果：指令 ACK 到达后更新点状态与回执（幂等：重复 ACK 覆盖为最新终态） */
	@Update("UPDATE ems_execution_record SET state = #{state}, result = #{result} WHERE exec_id = #{execId}")
	int updateStateAndResult(@Param("execId") Long execId, @Param("state") Integer state,
			@Param("result") String result);

}
