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
 * <li>POST /api/ota/tasks 创建批次任务（设备快照 + 立即开始）；</li>
 * <li>GET /api/ota/tasks 任务分页；GET /api/ota/tasks/{taskId} 任务详情；</li>
 * <li>GET /api/ota/tasks/{taskId}/devices 设备明细分页；</li>
 * <li>POST /api/ota/tasks/{taskId}/start 立即开始；POST /api/ota/tasks/{taskId}/pause 暂停； POST
 * /api/ota/tasks/{taskId}/resume 恢复；POST /api/ota/tasks/{taskId}/gray/advance 推进灰度； POST
 * /api/ota/tasks/{taskId}/cancel 取消；</li>
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

	/**
	 * 创建 OTA 批次升级任务（拍摄目标设备快照并写入任务与设备明细）。
	 * <p>
	 * 若请求未指定 planTime（scheduleTime 为 NULL），则创建后立即开始执行。
	 * @param req 创建请求，字段说明见 {@link OtaTaskCreateReq}
	 * @return {@link Result}<Long> 新创建的任务 ID（taskId）
	 */
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

	/**
	 * 批次任务分页查询，支持按任务名、状态筛选。
	 * @param taskName 任务名称（模糊匹配，查询参数，可选）
	 * @param status 任务状态 0待开始 1执行中 2已完成 3已暂停 4已取消（查询参数，可选）
	 * @param pageNum 页码（查询参数，默认 1）
	 * @param pageSize 每页条数（查询参数，默认 10）
	 * @return {@link Result}<Object> 分页数据，data 为
	 * {@link com.energyx.common.model.PageResult}<{@link OtaTaskRow}>（含 total/records）
	 */
	@GetMapping
	public Result<Object> page(@RequestParam(required = false) String taskName,
			@RequestParam(required = false) Integer status, @RequestParam(defaultValue = "1") long pageNum,
			@RequestParam(defaultValue = "10") long pageSize) {
		return Result.ok(taskService.page(taskName, status, pageNum, pageSize));
	}

	/**
	 * 查询单个批次任务详情。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<{@link OtaTaskRow}> 任务实体
	 */
	@GetMapping("/{taskId}")
	public Result<OtaTaskRow> get(@PathVariable Long taskId) {
		return Result.ok(taskService.get(taskId));
	}

	/**
	 * 查询批次任务下的设备升级明细分页，可按设备状态筛选。
	 * @param taskId 任务 ID（路径变量）
	 * @param state 设备升级状态 0待升级 1下载中 2升级中 3成功 4失败 5超时 6已取消（查询参数，可选）
	 * @param pageNum 页码（查询参数，默认 1）
	 * @param pageSize 每页条数（查询参数，默认 10）
	 * @return {@link Result}<Object> 分页数据，data 为
	 * {@link com.energyx.common.model.PageResult}<{@link OtaTaskDeviceRow}>（含
	 * total/records）
	 */
	@GetMapping("/{taskId}/devices")
	public Result<Object> devices(@PathVariable Long taskId, @RequestParam(required = false) Integer state,
			@RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "10") long pageSize) {
		return Result.ok(taskService.devices(taskId, state, pageNum, pageSize));
	}

	/**
	 * 立即开始执行批次任务（向目标设备下发升级指令）。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<Void> 操作结果
	 */
	@PostMapping("/{taskId}/start")
	public Result<Void> start(@PathVariable Long taskId) {
		taskService.start(taskId);
		return Result.ok();
	}

	/**
	 * 暂停执行中的批次任务（已下发的设备继续当前流程，新设备停止下发）。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<Void> 操作结果
	 */
	@PostMapping("/{taskId}/pause")
	public Result<Void> pause(@PathVariable Long taskId) {
		taskService.pause(taskId);
		return Result.ok();
	}

	/**
	 * 恢复已暂停的批次任务，继续向剩余设备下发升级。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<Void> 操作结果
	 */
	@PostMapping("/{taskId}/resume")
	public Result<Void> resume(@PathVariable Long taskId) {
		taskService.resume(taskId);
		return Result.ok();
	}

	/**
	 * 推进灰度批次任务到下一灰度批次（按比例扩大设备覆盖范围）。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<String> 灰度推进结果描述（如已推进到的比例或批次信息）
	 */
	@PostMapping("/{taskId}/gray/advance")
	public Result<String> advanceGray(@PathVariable Long taskId) {
		return Result.ok(taskService.advanceGray(taskId));
	}

	/**
	 * 取消批次任务（终止剩余设备下发，已成功设备不受影响）。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<Void> 操作结果
	 */
	@PostMapping("/{taskId}/cancel")
	public Result<Void> cancel(@PathVariable Long taskId) {
		taskService.cancel(taskId);
		return Result.ok();
	}

	/**
	 * 统计批次任务成功率及设备状态分布（基于任务与设备明细聚合）。
	 * @param taskId 任务 ID（路径变量）
	 * @return {@link Result}<Map<String, Object>> 统计项：成功率、各状态设备数等
	 */
	@GetMapping("/{taskId}/statistics")
	public Result<Map<String, Object>> statistics(@PathVariable Long taskId) {
		return Result.ok(taskService.statistics(taskId));
	}

}
