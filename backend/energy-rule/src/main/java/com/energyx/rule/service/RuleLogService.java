package com.energyx.rule.service;

import com.energyx.common.model.PageResult;
import com.energyx.rule.entity.SceneExecLogRow;
import com.energyx.rule.mapper.SceneExecLogMapper;
import com.energyx.rule.web.dto.RuleLogView;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则执行日志查询服务（引擎写入，管理端查询）。
 */
@Service
public class RuleLogService {

	private final SceneExecLogMapper logMapper;

	public RuleLogService(SceneExecLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	/** 分页查询执行日志 */
	public PageResult<RuleLogView> page(Long tenantId, Long ruleId, String triggerType, Long deviceId,
			LocalDateTime startTime, LocalDateTime endTime, long page, long size) {
		long offset = (page - 1) * size;
		List<SceneExecLogRow> rows = logMapper.selectPage(tenantId, ruleId, triggerType, deviceId, startTime, endTime,
				offset, size);
		long total = logMapper.countPage(tenantId, ruleId, triggerType, deviceId, startTime, endTime);
		List<RuleLogView> views = new ArrayList<>(rows.size());
		for (SceneExecLogRow row : rows) {
			views.add(toView(row));
		}
		return PageResult.of(total, page, size, views);
	}

	private RuleLogView toView(SceneExecLogRow row) {
		RuleLogView view = new RuleLogView();
		view.setLogId(row.getLogId());
		view.setRuleId(row.getRuleId());
		view.setRuleCode(row.getRuleCode());
		view.setTenantId(row.getTenantId());
		view.setTriggerType(row.getTriggerType());
		view.setDeviceId(row.getDeviceId());
		view.setMatched(row.getMatched());
		view.setActionResult(row.getActionResult());
		view.setCostMs(row.getCostMs());
		view.setTraceId(row.getTraceId());
		view.setCreateTime(row.getCreateTime());
		return view;
	}

}
