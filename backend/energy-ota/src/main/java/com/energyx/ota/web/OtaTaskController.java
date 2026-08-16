package com.energyx.ota.web;

import com.energyx.common.model.Result;
import com.energyx.ota.entity.OtaTaskDeviceRow;
import com.energyx.ota.entity.OtaTaskRow;
import com.energyx.ota.service.OtaTaskService;
import com.energyx.ota.web.dto.OtaTaskCreateReq;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OTA 批次任务 API（网关路由 /api/ota/** → energy-ota）。
 *
 * <ul>
 * <li>POST /api/ota/tasks 创建任务（设备快照 + 立即开始）；</li>
 * <li>GET /api/ota/tasks 任务分页；GET /api/ota/tasks/{taskId} 详情；</li>
 * <li>GET /api/ota/tasks/{taskId}/devices 设备明细分页；</li>
 * <li>POST /api/ota/tasks/{taskId}/start 立即开始；POST /api/ota/tasks/{taskId}/cancel
 * 取消；</li>
 * <li>GET /api/ota/tasks/{taskId}/statistics 成功率/统计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ota/tasks")
public class OtaTaskController {

	private final OtaTaskService taskService;

	public OtaTaskController(OtaTaskService taskService) {
		this.taskService = taskService;
	}

	@PostMapping
	public Result<Long> create(@RequestBody OtaTaskCreateReq req) {
		Long taskId = taskService.create(req);
		// 创建后无计划时间则立即开始（调度器 S4 完善定时触发）
		OtaTaskRow task = taskService.get(taskId);
		if (task.getScheduleTime() == null) {
			taskService.start(taskId);
		}
		return Result.ok(taskId);
	}

	@GetMapping
	public Result<Object> page(@RequestParam(required = false) String taskName,
			@RequestParam(required = false) Integer status, @RequestParam(defaultValue = "1") long pageNum,
			@RequestParam(defaultValue = "10") long pageSize) {
		return Result.ok(taskService.page(taskName, status, pageNum, pageSize));
	}

	@GetMapping("/{taskId}")
	public Result<OtaTaskRow> get(@PathVariable Long taskId) {
		return Result.ok(taskService.get(taskId));
	}

	@GetMapping("/{taskId}/devices")
	public Result<Object> devices(@PathVariable Long taskId, @RequestParam(required = false) Integer state,
			@RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "10") long pageSize) {
		return Result.ok(taskService.devices(taskId, state, pageNum, pageSize));
	}

	@PostMapping("/{taskId}/start")
	public Result<Void> start(@PathVariable Long taskId) {
		taskService.start(taskId);
		return Result.ok();
	}

	@PostMapping("/{taskId}/pause")
	public Result<Void> pause(@PathVariable Long taskId) {
		taskService.pause(taskId);
		return Result.ok();
	}

	@PostMapping("/{taskId}/resume")
	public Result<Void> resume(@PathVariable Long taskId) {
		taskService.resume(taskId);
		return Result.ok();
	}

	@PostMapping("/{taskId}/gray/advance")
	public Result<String> advanceGray(@PathVariable Long taskId) {
		return Result.ok(taskService.advanceGray(taskId));
	}

	@PostMapping("/{taskId}/cancel")
	public Result<Void> cancel(@PathVariable Long taskId) {
		taskService.cancel(taskId);
		return Result.ok();
	}

	@GetMapping("/{taskId}/statistics")
	public Result<Map<String, Object>> statistics(@PathVariable Long taskId) {
		return Result.ok(taskService.statistics(taskId));
	}

}
