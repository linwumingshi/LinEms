package com.energyx.ota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ota.client.DeviceFeignClient;
import com.energyx.ota.client.dto.DeviceQuery;
import com.energyx.ota.client.dto.DeviceUpdateReq;
import com.energyx.ota.client.dto.DeviceView;
import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.entity.OtaPackageRow;
import com.energyx.ota.entity.OtaTaskDeviceRow;
import com.energyx.ota.entity.OtaTaskRow;
import com.energyx.ota.mapper.OtaPackageMapper;
import com.energyx.ota.mapper.OtaTaskDeviceMapper;
import com.energyx.ota.mapper.OtaTaskMapper;
import com.energyx.ota.mqtt.OtaDownPublisher;
import com.energyx.ota.web.dto.OtaTaskCreateReq;
import com.energyx.common.message.OtaDownMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTA 批次升级任务：创建（设备快照）/下发推进/进度与结果处理/成功判定/版本回写/统计。
 *
 * <p>
 * 状态约定：任务 status 0待开始 1执行中 2已完成 3已暂停 4已取消； 明细 state 0待升级 1下载中 2升级中 3成功 4失败 5超时 6已取消。
 * 成功判据（同阿里云）：设备上报新版本 == 目标版本（inform 或 result 携带）。
 * </p>
 */
@Slf4j
@Service
public class OtaTaskService {

	/** 任务状态 */
	public static final int TASK_PENDING = 0;

	public static final int TASK_RUNNING = 1;

	public static final int TASK_DONE = 2;

	public static final int TASK_PAUSED = 3;

	public static final int TASK_CANCELED = 4;

	/** 明细状态 */
	public static final int DEV_PENDING = 0;

	public static final int DEV_DOWNLOADING = 1;

	public static final int DEV_UPGRADING = 2;

	public static final int DEV_SUCCESS = 3;

	public static final int DEV_FAILED = 4;

	public static final int DEV_TIMEOUT = 5;

	public static final int DEV_CANCELED = 6;

	private final OtaTaskMapper taskMapper;

	private final OtaTaskDeviceMapper deviceMapper;

	private final OtaPackageMapper packageMapper;

	private final OtaDownPublisher downPublisher;

	private final OtaVersionCache versionCache;

	private final DeviceFeignClient deviceFeignClient;

	private final OtaProperties props;

	private final OtaNotifyService notifyService;

	private final OtaPackageService packageService;

	private final OtaUrlSignService urlSignService;

	private final OtaSignService signService;

	public OtaTaskService(OtaTaskMapper taskMapper, OtaTaskDeviceMapper deviceMapper, OtaPackageMapper packageMapper,
			OtaDownPublisher downPublisher, OtaVersionCache versionCache, DeviceFeignClient deviceFeignClient,
			OtaProperties props, OtaNotifyService notifyService, OtaPackageService packageService,
			OtaUrlSignService urlSignService, OtaSignService signService) {
		this.taskMapper = taskMapper;
		this.deviceMapper = deviceMapper;
		this.packageMapper = packageMapper;
		this.downPublisher = downPublisher;
		this.versionCache = versionCache;
		this.deviceFeignClient = deviceFeignClient;
		this.props = props;
		this.notifyService = notifyService;
		this.packageService = packageService;
		this.urlSignService = urlSignService;
		this.signService = signService;
	}

	// ---------------- 任务 CRUD ----------------

	/**
	 * 创建批次任务：校验升级包 → 解析目标设备（全部/指定/灰度比例）→ 快照明细（PENDING）→ 入库。 返回任务 ID；任务默认待开始，scheduleTime
	 * 为空则立即开始。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Long create(OtaTaskCreateReq req) {
		Long tenantId = requireTenant();
		OtaPackageRow pkg = packageMapper.selectById(req.getPackageId());
		if (pkg == null || !pkg.getTenantId().equals(tenantId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "升级包不存在");
		}
		if (pkg.getStatus() != 1) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "升级包已停用");
		}
		List<Long> deviceIds = resolveDevices(tenantId, req);
		if (deviceIds.isEmpty()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "未匹配到目标设备");
		}
		OtaTaskRow task = new OtaTaskRow();
		task.setTenantId(tenantId);
		task.setPackageId(pkg.getPackageId());
		task.setTaskName(req.getTaskName() == null || req.getTaskName().isBlank() ? "OTA-" + pkg.getVersion()
				: req.getTaskName());
		task.setTaskType(req.getTaskType() == null ? 1 : req.getTaskType());
		task.setDownloadPolicy(req.getDownloadPolicy() == null ? 1 : req.getDownloadPolicy());
		task.setGrayRatio(req.getTaskType() != null && req.getTaskType() == 3 ? req.getGrayRatio() : null);
		task.setDeviceCount(deviceIds.size());
		task.setSuccessCount(0);
		task.setFailCount(0);
		task.setStatus(TASK_PENDING);
		task.setRetryTimes(req.getRetryTimes() == null ? 2 : req.getRetryTimes());
		task.setRetryIntervalMin(req.getRetryIntervalMin() == null ? 5 : req.getRetryIntervalMin());
		task.setDownloadTimeoutMin(req.getDownloadTimeoutMin() == null ? 60 : req.getDownloadTimeoutMin());
		task.setUpgradeTimeoutMin(req.getUpgradeTimeoutMin() == null ? 30 : req.getUpgradeTimeoutMin());
		task.setAutoPauseOnFail(req.getAutoPauseOnFail() == null ? 1 : req.getAutoPauseOnFail());
		task.setScheduleTime(req.getScheduleTime());
		task.setCreateBy(req.getCreateBy() == null ? 0L : req.getCreateBy());
		taskMapper.insert(task);

		// 快照目标设备（PENDING），version_before 取版本缓存
		for (Long deviceId : deviceIds) {
			OtaTaskDeviceRow row = new OtaTaskDeviceRow();
			row.setTaskId(task.getTaskId());
			row.setDeviceId(deviceId);
			row.setTenantId(tenantId);
			row.setState(DEV_PENDING);
			row.setProgress(0);
			row.setVersionBefore(versionCache.getVersion(deviceId));
			row.setRetryCount(0);
			deviceMapper.insert(row);
		}
		log.info("[OTA] 任务创建 taskId={} name={} package={} devices={}", task.getTaskId(), task.getTaskName(),
				pkg.getVersion(), deviceIds.size());
		return task.getTaskId();
	}

	/** 任务分页 */
	public PageResult<OtaTaskRow> page(String taskName, Integer status, long pageNum, long pageSize) {
		Long tenantId = requireTenant();
		Page<OtaTaskRow> p = taskMapper.selectPage(new Page<>(pageNum, pageSize),
				new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getTenantId, tenantId)
					.like(StringUtils.hasText(taskName), OtaTaskRow::getTaskName, taskName)
					.eq(status != null, OtaTaskRow::getStatus, status)
					.orderByDesc(OtaTaskRow::getCreateTime));
		return PageResult.of(p);
	}

	/** 任务详情（含成功/失败/进行中统计） */
	public OtaTaskRow get(Long taskId) {
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null || !task.getTenantId().equals(requireTenant())) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
		}
		return task;
	}

	/** 取消任务（待开始/执行中可取消，未终态明细置已取消） */
	@Transactional(rollbackFor = Exception.class)
	public void cancel(Long taskId) {
		OtaTaskRow task = get(taskId);
		if (task.getStatus() != TASK_PENDING && task.getStatus() != TASK_RUNNING) {
			throw new BusinessException(ErrorCode.CONFLICT, "任务当前状态不可取消");
		}
		task.setStatus(TASK_CANCELED);
		taskMapper.updateById(task);
		OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
		upd.setState(DEV_CANCELED);
		deviceMapper.update(upd, new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
			.in(OtaTaskDeviceRow::getState, DEV_PENDING, DEV_DOWNLOADING, DEV_UPGRADING));
	}

	/** 设备明细分页 */
	public PageResult<OtaTaskDeviceRow> devices(Long taskId, Integer state, long pageNum, long pageSize) {
		get(taskId);
		Page<OtaTaskDeviceRow> p = deviceMapper.selectPage(new Page<>(pageNum, pageSize),
				new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
					.eq(state != null, OtaTaskDeviceRow::getState, state)
					.orderByAsc(OtaTaskDeviceRow::getDeviceId));
		return PageResult.of(p);
	}

	// ---------------- 任务推进 ----------------

	/** 立即开始任务（status 0→1），对全部 PENDING 设备下发升级通知 */
	@Transactional(rollbackFor = Exception.class)
	public void start(Long taskId) {
		OtaTaskRow task = get(taskId);
		if (task.getStatus() != TASK_PENDING) {
			throw new BusinessException(ErrorCode.CONFLICT, "任务非待开始状态");
		}
		task.setStatus(TASK_RUNNING);
		taskMapper.updateById(task);
		// 设备下发（含 Feign 查差分/设备名、MQ 发布）放到事务提交后，避免事务内远程调用持有 DB 连接
		final Long committedTaskId = task.getTaskId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				onTaskStartedAfterCommit(committedTaskId);
			}
		});
	}

	/**
	 * 调度器/手动触发：对任务内 PENDING 设备下发（在线直推，离线保持 PENDING 等补推）； 灰度任务按当前 gray_ratio 截断只下发前 N
	 * 台（device_id 升序，S4 灰度批次控制）
	 */
	public void dispatchPending(OtaTaskRow task) {
		List<OtaTaskDeviceRow> pendings = deviceMapper
			.selectList(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
				.eq(OtaTaskDeviceRow::getState, DEV_PENDING)
				.orderByAsc(OtaTaskDeviceRow::getDeviceId));
		int limit = grayBatchLimit(task);
		int dispatched = 0;
		for (OtaTaskDeviceRow row : pendings) {
			if (limit > 0 && dispatched >= limit) {
				// 灰度任务：超过当前批次上限的设备保持 PENDING，等待灰度推进
				break;
			}
			if (dispatch(task, row)) {
				dispatched++;
			}
		}
	}

	/** 灰度任务当前批次可下发台数（非灰度返回 -1 表示不限）；空任务/无比例按 1 台兜底 */
	private int grayBatchLimit(OtaTaskRow task) {
		if (task.getTaskType() == null || task.getTaskType() != 3) {
			return -1;
		}
		int ratio = task.getGrayRatio() == null ? 0 : Math.max(0, Math.min(100, task.getGrayRatio()));
		if (ratio >= 100) {
			return -1;
		}
		int total = task.getDeviceCount() == null ? 0 : task.getDeviceCount();
		int count = (int) Math.max(1, Math.ceil(total * ratio / 100.0));
		return Math.max(1, count);
	}

	/** 单设备下发：构造升级通知信封（S5：差分匹配 + 签名 URL + RSA 信息）→ 定向/广播；在线置下载中，离线保持待升级 */
	public boolean dispatch(OtaTaskRow task, OtaTaskDeviceRow row) {
		OtaPackageRow pkg = packageMapper.selectById(task.getPackageId());
		if (pkg == null) {
			return false;
		}
		// S5-1 差分优先：设备当前版本（versionBefore）有匹配差分包则下差分，无则退化全量
		OtaPackageRow deliver = pkg;
		boolean useDiff = task.getDownloadPolicy() != null && task.getDownloadPolicy() == 1;
		if (useDiff && pkg.getPackageType() == 1 && StringUtils.hasText(row.getVersionBefore())) {
			OtaPackageRow diff = packageService.findDiff(task.getTenantId(), pkg.getProductKey(), pkg.getVersion(),
					row.getVersionBefore());
			if (diff != null) {
				deliver = diff;
			}
		}
		OtaDownMessage msg = new OtaDownMessage();
		msg.setTaskId(task.getTaskId());
		msg.setDeviceId(row.getDeviceId());
		msg.setTenantId(task.getTenantId());
		msg.setProductKey(pkg.getProductKey());
		msg.setPackageId(deliver.getPackageId());
		msg.setVersion(pkg.getVersion());
		msg.setPackageType(deliver.getPackageType());
		msg.setBaseVersion(deliver.getBaseVersion());
		msg.setModule(pkg.getModule());
		// S5-4 签名 URL（时效 HMAC）
		msg.setUrl(urlSignService.signUrl(deliver.getFilePath()));
		msg.setSize(deliver.getFileSize());
		// 差分包：sha256=差分自身，targetSha256=合并产物（全量包）校验
		msg.setSha256(deliver.getSha256());
		msg.setTargetSha256(pkg.getPackageType() == 1 ? pkg.getSha256() : pkg.getSha256());
		msg.setSignMethod("SHA256+RSA");
		msg.setSegmentSize(props.getSegmentSize());
		// S5-2 RSA 签名与公钥（设备侧验签安装）
		msg.getExtData().put("signature", deliver.getSignature() == null ? "" : deliver.getSignature());
		msg.getExtData().put("publicKey", signService.publicKeyBase64());
		msg.getExtData().put("ota_notice", pkg.getDescription());
		// 下发需要设备名（构造下行 topic）——快照时未存设备名，经 Feign 查询补充
		String deviceName = resolveDeviceName(pkg.getProductKey(), row.getDeviceId());
		msg.setDeviceName(deviceName);
		boolean online = downPublisher.publish(msg);
		if (online) {
			OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
			upd.setState(DEV_DOWNLOADING);
			upd.setStartTime(LocalDateTime.now());
			deviceMapper.update(upd,
					new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
						.eq(OtaTaskDeviceRow::getDeviceId, row.getDeviceId()));
		}
		return online;
	}

	// ---------------- 灰度推进与运维控制（S4） ----------------

	/** 灰度档位（1% → 10% → 50% → 100%，渐进式推送） */
	private static final int[] GRAY_STEPS = { 1, 10, 50, 100 };

	/**
	 * 灰度推进（S4-1）：当前批次设备全部完结后检查成功率—— 成功率 ≥95% 推进下一档并下发新批次；成功率 <95% 且开启自动暂停 → 暂停任务 + 告警。
	 * @param taskId 灰度任务 ID
	 * @return 推进说明（"已推进至 N%" / "已达 100%" / "等待本批完结" / "已自动暂停"）
	 */
	public String advanceGray(Long taskId) {
		// 调度扫描无租户上下文：直接查库；HTTP 调用（有租户）在 pause 时仍会校验
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
		}
		if (task.getTaskType() == null || task.getTaskType() != 3) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "非灰度任务不支持灰度推进");
		}
		if (task.getStatus() != TASK_RUNNING) {
			throw new BusinessException(ErrorCode.CONFLICT, "任务非执行中状态");
		}
		int currentRatio = task.getGrayRatio() == null ? 0 : task.getGrayRatio();
		if (currentRatio >= 100) {
			return "灰度已达 100%";
		}
		// 本批已完结设备成功率（成功 / 已下发完结数）
		long finished = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.in(OtaTaskDeviceRow::getState, DEV_SUCCESS, DEV_FAILED, DEV_TIMEOUT));
		long success = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.eq(OtaTaskDeviceRow::getState, DEV_SUCCESS));
		// 本批（当前比例对应的设备数）尚未全部完结 → 等待（离线设备保持 PENDING 等补推，不计入完结）
		int batchSize = (int) Math.max(1, Math.ceil(task.getDeviceCount() * currentRatio / 100.0));
		if (finished < batchSize) {
			return "等待本批 " + finished + "/" + batchSize + " 台完结";
		}
		// 成功率判定：有完结设备且成功率 <95% → 自动暂停（S4-3 告警）
		if (finished > 0 && finished == batchSize && task.getAutoPauseOnFail() != null
				&& task.getAutoPauseOnFail() == 1) {
			double rate = success * 100.0 / finished;
			if (rate < 95.0) {
				pause(taskId);
				log.warn("[OTA] 灰度任务自动暂停 taskId={} 成功率={}% (<95%)", taskId, rate);
				notifyService.sendAlert("OTA 灰度任务自动暂停", "任务[" + task.getTaskName() + "]成功率 "
						+ String.format("%.1f", rate) + "% <95%，已自动暂停，请人工介入（任务ID:" + taskId + "）");
				return "成功率 " + String.format("%.1f", rate) + "% <95%，已自动暂停";
			}
		}
		// 推进下一档
		int nextRatio = nextGrayStep(currentRatio);
		if (nextRatio <= currentRatio) {
			return "灰度已达 100%";
		}
		OtaTaskRow upd = new OtaTaskRow();
		upd.setGrayRatio(nextRatio);
		taskMapper.update(upd, new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getTaskId, taskId));
		task.setGrayRatio(nextRatio);
		dispatchPending(task);
		log.info("[OTA] 灰度推进 taskId={} {}% → {}%", taskId, currentRatio, nextRatio);
		return "已推进至 " + nextRatio + "%";
	}

	/** 下一个灰度档位（GRAY_STEPS 中大于当前的最小档，无则返回当前值） */
	private int nextGrayStep(int current) {
		for (int step : GRAY_STEPS) {
			if (step > current) {
				return step;
			}
		}
		return current;
	}

	/** 暂停任务（status 1→3，未终态明细保持待升级不下发）；调度扫描无租户上下文时内部查库 */
	public void pause(Long taskId) {
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
		}
		if (task.getStatus() != TASK_RUNNING) {
			throw new BusinessException(ErrorCode.CONFLICT, "任务非执行中状态");
		}
		OtaTaskRow upd = new OtaTaskRow();
		upd.setStatus(TASK_PAUSED);
		taskMapper.update(upd, new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getTaskId, taskId));
		log.info("[OTA] 任务已暂停 taskId={}", taskId);
	}

	/** 恢复任务（status 3→1，重新对当前灰度批次内 PENDING 设备下发） */
	public void resume(Long taskId) {
		OtaTaskRow task = get(taskId);
		if (task.getStatus() != TASK_PAUSED) {
			throw new BusinessException(ErrorCode.CONFLICT, "任务非暂停状态");
		}
		OtaTaskRow upd = new OtaTaskRow();
		upd.setStatus(TASK_RUNNING);
		taskMapper.update(upd, new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getTaskId, taskId));
		task.setStatus(TASK_RUNNING);
		dispatchPending(task);
		log.info("[OTA] 任务已恢复 taskId={}", taskId);
	}

	/** 重试指定设备（S4-2 调度器调用）：失败/超时且重试未耗尽 → 重新下发 */
	public void retryDevice(OtaTaskRow task, OtaTaskDeviceRow row) {
		if (row.getRetryCount() == null || row.getRetryCount() < task.getRetryTimes()) {
			OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
			upd.setState(DEV_PENDING);
			upd.setRetryCount((row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1);
			upd.setRetryAt(LocalDateTime.now()
				.plusMinutes(task.getRetryIntervalMin() == null ? 5 : task.getRetryIntervalMin()));
			upd.setFailMsg(null);
			deviceMapper.update(upd,
					new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
						.eq(OtaTaskDeviceRow::getDeviceId, row.getDeviceId()));
			row.setState(DEV_PENDING);
			row.setRetryCount(upd.getRetryCount());
			row.setRetryAt(upd.getRetryAt());
			dispatch(task, row);
			log.info("[OTA] 设备重试下发 taskId={} deviceId={} 第{}次", task.getTaskId(), row.getDeviceId(),
					row.getRetryCount());
		}
	}

	// ---------------- 设备报文处理（OtaUplinkHandler 回调） ----------------

	/** 进度上报：PENDING/下载中 → 下载中/升级中，更新进度 */
	public void onProgress(Long taskId, Long deviceId, int progress, String state) {
		OtaTaskDeviceRow row = findDevice(taskId, deviceId);
		if (row == null || row.getState() == DEV_SUCCESS || row.getState() == DEV_FAILED
				|| row.getState() == DEV_TIMEOUT) {
			return;
		}
		int newState = "UPGRADING".equalsIgnoreCase(state) ? DEV_UPGRADING : DEV_DOWNLOADING;
		OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
		upd.setState(newState);
		upd.setProgress(Math.max(0, Math.min(100, progress)));
		deviceMapper.update(upd, new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
			.eq(OtaTaskDeviceRow::getDeviceId, deviceId));
	}

	/** 结果上报：success=true → 校验版本 → 成功 + 版本回写；false → 失败（重试逻辑 S4） */
	public void onResult(Long taskId, Long deviceId, boolean success, String version, String code, String msg) {
		OtaTaskDeviceRow row = findDevice(taskId, deviceId);
		if (row == null || row.getState() == DEV_SUCCESS || row.getState() == DEV_CANCELED) {
			return;
		}
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null) {
			return;
		}
		OtaPackageRow pkg = packageMapper.selectById(task.getPackageId());
		if (success && pkg != null && version != null && version.equals(pkg.getVersion())) {
			finishDevice(task, row, DEV_SUCCESS, version);
		}
		else {
			OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
			upd.setState(DEV_FAILED);
			upd.setFailCode(code);
			upd.setFailMsg(msg);
			upd.setFinishTime(LocalDateTime.now());
			upd.setVersionAfter(version);
			// 重试调度（S4-2）：未耗尽重试次数则登记下次重试时间，由扫描器补发
			int retries = row.getRetryCount() == null ? 0 : row.getRetryCount();
			if (retries < task.getRetryTimes()) {
				long interval = task.getRetryIntervalMin() == null ? 5 : task.getRetryIntervalMin();
				upd.setRetryAt(LocalDateTime.now().plusMinutes(interval));
			}
			deviceMapper.update(upd, new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.eq(OtaTaskDeviceRow::getDeviceId, deviceId));
			refreshTaskCounters(taskId);
		}
	}

	/** 版本上报：设备上报版本 == 目标版本 → 视为升级成功（唯一成功判据，同阿里云） */
	public void onInform(Long deviceId, String version) {
		if (version == null || version.isBlank()) {
			return;
		}
		// deviceId 全局唯一（跨租户不冲突），消费线程无租户上下文故不按租户过滤
		List<OtaTaskDeviceRow> actives = deviceMapper
			.selectList(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getDeviceId, deviceId)
				.in(OtaTaskDeviceRow::getState, DEV_PENDING, DEV_DOWNLOADING, DEV_UPGRADING));
		for (OtaTaskDeviceRow row : actives) {
			OtaTaskRow task = taskMapper.selectById(row.getTaskId());
			if (task == null) {
				continue;
			}
			OtaPackageRow pkg = packageMapper.selectById(task.getPackageId());
			if (pkg != null && version.equals(pkg.getVersion())) {
				log.info("[OTA] 版本上报判定成功 deviceId={} version={} taskId={}", deviceId, version, task.getTaskId());
				finishDevice(task, row, DEV_SUCCESS, version);
			}
		}
	}

	/** 主动拉取：设备存在 PENDING 任务 → 立即补推 */
	public void onPull(Long deviceId) {
		List<OtaTaskDeviceRow> pendings = deviceMapper
			.selectList(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getDeviceId, deviceId)
				.eq(OtaTaskDeviceRow::getState, DEV_PENDING));
		for (OtaTaskDeviceRow row : pendings) {
			OtaTaskRow task = taskMapper.selectById(row.getTaskId());
			if (task != null && task.getStatus() == TASK_RUNNING) {
				dispatch(task, row);
			}
		}
	}

	/** 设备上线补推：state=PENDING 且任务执行中 → 下发 */
	public void onDeviceOnline(Long deviceId) {
		onPull(deviceId);
	}

	// ---------------- 内部工具 ----------------

	/** 设备成功终态：更新明细 + 版本回写 device + 刷新任务统计与完成判定 */
	private void finishDevice(OtaTaskRow task, OtaTaskDeviceRow row, int state, String version) {
		OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
		upd.setState(state);
		upd.setProgress(100);
		upd.setVersionAfter(version);
		upd.setFinishTime(LocalDateTime.now());
		deviceMapper.update(upd,
				new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
					.eq(OtaTaskDeviceRow::getDeviceId, row.getDeviceId()));
		// 版本回写 device 服务（失败不阻断，下次 inform 仍可判定）
		writeBackFirmwareVersion(row.getDeviceId(), version);
		refreshTaskCounters(task.getTaskId());
	}

	/** 刷新任务成功/失败计数，全终态置任务完成 */
	private void refreshTaskCounters(Long taskId) {
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null) {
			return;
		}
		long success = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.eq(OtaTaskDeviceRow::getState, DEV_SUCCESS));
		long fail = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.in(OtaTaskDeviceRow::getState, DEV_FAILED, DEV_TIMEOUT));
		long total = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId));
		OtaTaskRow upd = new OtaTaskRow();
		upd.setSuccessCount((int) success);
		upd.setFailCount((int) fail);
		if (success + fail + countActive(taskId) == total) {
			upd.setStatus(TASK_DONE);
		}
		taskMapper.update(upd, new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getTaskId, taskId));
	}

	/** 进行中（未终态：待升级/下载中/升级中）明细数 */
	private long countActive(Long taskId) {
		return deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.in(OtaTaskDeviceRow::getState, DEV_PENDING, DEV_DOWNLOADING, DEV_UPGRADING));
	}

	/** 回写 device.firmware_version（Feign，失败记日志不阻断） */
	private void writeBackFirmwareVersion(Long deviceId, String version) {
		try {
			DeviceUpdateReq req = new DeviceUpdateReq();
			req.setFirmwareVersion(version);
			deviceFeignClient.update(deviceId, req);
		}
		catch (Exception e) {
			log.warn("[OTA] 版本回写 device 失败 deviceId={} version={}", deviceId, version, e);
		}
	}

	/** 按产品 + deviceId 解析设备名（构造下行 topic；缓存缺失回退 Feign） */
	private String resolveDeviceName(String productKey, Long deviceId) {
		try {
			DeviceQuery q = new DeviceQuery();
			q.setProductKey(productKey);
			q.setPageSize(500);
			Result<PageResult<DeviceView>> r = deviceFeignClient.page(q);
			if (r != null && r.isSuccess() && r.getData() != null) {
				for (DeviceView v : r.getData().getRecords()) {
					if (deviceId.equals(v.getDeviceId())) {
						return v.getDeviceName();
					}
				}
			}
		}
		catch (Exception e) {
			log.warn("[OTA] 设备名解析失败 deviceId={}", deviceId, e);
		}
		return null;
	}

	/** 解析任务目标设备列表（全部=产品下全部设备；指定=传入列表；灰度=产品下全部设备，批次截断由下发阶段按比例处理） */
	private List<Long> resolveDevices(Long tenantId, OtaTaskCreateReq req) {
		OtaPackageRow pkg = packageMapper.selectById(req.getPackageId());
		int taskType = req.getTaskType() == null ? 1 : req.getTaskType();
		if (taskType == 2 && req.getDeviceIds() != null && !req.getDeviceIds().isEmpty()) {
			return new ArrayList<>(req.getDeviceIds());
		}
		List<Long> all = listProductDevices(tenantId, pkg.getProductKey());
		// 灰度任务快照全部设备（device_count=全部），批次激活由 dispatchPending 按 gray_ratio 截断
		return all;
	}

	/**
	 * 创建任务设备选择器：按产品 + 关键字 + 状态拉取设备列表（供前端弹窗多选）。
	 * <p>
	 * 租户范围由上下文注入；结果按设备名升序，最多返回 2000 台避免超大产品卡顿。
	 * </p>
	 * @param productKey 产品标识（必填，取自所选升级包）
	 * @param keyword 设备名关键字（可选）
	 * @param status 设备生命周期状态（可选，见 DeviceStatus 枚举 code）
	 * @return 设备投影列表（含 deviceId/deviceName/status/firmwareVersion 等）
	 */
	public List<DeviceView> listPickerDevices(String productKey, String keyword, Integer status) {
		requireTenant();
		if (productKey == null || productKey.isBlank()) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "productKey 不能为空");
		}
		List<DeviceView> all = new ArrayList<>();
		int page = 1;
		int pageSize = 500;
		while (page * pageSize <= 2000) {
			DeviceQuery q = new DeviceQuery();
			q.setPageNum(page);
			q.setPageSize(pageSize);
			q.setProductKey(productKey);
			q.setKeyword(keyword);
			q.setStatus(status);
			Result<PageResult<DeviceView>> r = deviceFeignClient.page(q);
			if (r == null || !r.isSuccess() || r.getData() == null) {
				break;
			}
			List<DeviceView> records = r.getData().getRecords();
			if (records == null || records.isEmpty()) {
				break;
			}
			all.addAll(records);
			if (records.size() < pageSize) {
				break;
			}
			page++;
		}
		all.sort(java.util.Comparator.comparing(DeviceView::getDeviceName,
				java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
		return all;
	}

	/** 按产品分页拉全设备（Feign，最多 1000 台） */
	private List<Long> listProductDevices(Long tenantId, String productKey) {
		List<Long> ids = new ArrayList<>();
		int page = 1;
		int pageSize = 500;
		while (page * pageSize <= 1000) {
			DeviceQuery q = new DeviceQuery();
			q.setPageNum(page);
			q.setPageSize(pageSize);
			q.setProductKey(productKey);
			Result<PageResult<DeviceView>> r = deviceFeignClient.page(q);
			if (r == null || !r.isSuccess() || r.getData() == null) {
				break;
			}
			List<DeviceView> records = r.getData().getRecords();
			if (records == null || records.isEmpty()) {
				break;
			}
			for (DeviceView v : records) {
				ids.add(v.getDeviceId());
			}
			if (records.size() < pageSize) {
				break;
			}
			page++;
		}
		return ids;
	}

	private OtaTaskDeviceRow findDevice(Long taskId, Long deviceId) {
		return deviceMapper.selectOne(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
			.eq(OtaTaskDeviceRow::getDeviceId, deviceId));
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

	/** 任务统计（版本分布/成功率），管理端展示用 */
	public Map<String, Object> statistics(Long taskId) {
		OtaTaskRow task = get(taskId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("taskId", taskId);
		out.put("deviceCount", task.getDeviceCount());
		out.put("successCount", task.getSuccessCount());
		out.put("failCount", task.getFailCount());
		long success = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId)
				.eq(OtaTaskDeviceRow::getState, DEV_SUCCESS));
		long total = deviceMapper
			.selectCount(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, taskId));
		out.put("successRate", total == 0 ? 0 : Math.round(success * 10000.0 / total) / 100.0);
		out.put("status", task.getStatus());
		return out;
	}

	// ---------------- 运维扫描（S4-2 OtaScheduler 调用，无租户上下文） ----------------

	/**
	 * 超时扫描：下载中超时（start_time + download_timeout_min）与升级中超时（start_time +
	 * upgrade_timeout_min）的设备——重试未耗尽则重试，耗尽则置 TIMEOUT 终态。
	 * @return 本轮处理的设备数
	 */
	public int scanTimeout() {
		List<OtaTaskRow> tasks = taskMapper
			.selectList(new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getStatus, TASK_RUNNING));
		int handled = 0;
		LocalDateTime now = LocalDateTime.now();
		for (OtaTaskRow task : tasks) {
			long dlTimeout = task.getDownloadTimeoutMin() == null ? 60 : task.getDownloadTimeoutMin();
			long upTimeout = task.getUpgradeTimeoutMin() == null ? 30 : task.getUpgradeTimeoutMin();
			List<OtaTaskDeviceRow> rows = deviceMapper
				.selectList(new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
					.in(OtaTaskDeviceRow::getState, DEV_DOWNLOADING, DEV_UPGRADING));
			for (OtaTaskDeviceRow row : rows) {
				LocalDateTime deadline = row.getState() == DEV_DOWNLOADING ? row.getStartTime().plusMinutes(dlTimeout)
						: row.getStartTime().plusMinutes(upTimeout);
				if (deadline.isAfter(now)) {
					continue;
				}
				if (row.getRetryCount() != null && row.getRetryCount() >= task.getRetryTimes()) {
					// 重试耗尽 → 置 TIMEOUT 终态
					OtaTaskDeviceRow upd = new OtaTaskDeviceRow();
					upd.setState(DEV_TIMEOUT);
					upd.setFailCode("TIMEOUT");
					upd.setFailMsg(row.getState() == DEV_DOWNLOADING ? "下载超时" : "升级超时");
					upd.setFinishTime(now);
					deviceMapper.update(upd,
							new LambdaQueryWrapper<OtaTaskDeviceRow>().eq(OtaTaskDeviceRow::getTaskId, task.getTaskId())
								.eq(OtaTaskDeviceRow::getDeviceId, row.getDeviceId()));
					handled++;
				}
				else {
					// 重试未耗尽 → 重新下发
					retryDevice(task, row);
					handled++;
				}
			}
		}
		if (handled > 0) {
			log.info("[OTA] 超时扫描处理 {} 台设备", handled);
		}
		return handled;
	}

	/**
	 * 重试扫描：失败（FAILED/TIMEOUT）且重试未耗尽且 retry_at 已到 → 重新下发。
	 * @return 本轮重试的设备数
	 */
	public int scanRetry() {
		LocalDateTime now = LocalDateTime.now();
		List<OtaTaskDeviceRow> rows = deviceMapper.selectList(
				new LambdaQueryWrapper<OtaTaskDeviceRow>().in(OtaTaskDeviceRow::getState, DEV_FAILED, DEV_TIMEOUT)
					.isNotNull(OtaTaskDeviceRow::getRetryAt)
					.le(OtaTaskDeviceRow::getRetryAt, now));
		int retried = 0;
		for (OtaTaskDeviceRow row : rows) {
			OtaTaskRow task = taskMapper.selectById(row.getTaskId());
			if (task == null || task.getStatus() != TASK_RUNNING) {
				continue;
			}
			if (row.getRetryCount() != null && row.getRetryCount() >= task.getRetryTimes()) {
				continue;
			}
			retryDevice(task, row);
			retried++;
		}
		if (retried > 0) {
			log.info("[OTA] 重试扫描重发 {} 台设备", retried);
		}
		return retried;
	}

	/** 灰度推进扫描：对执行中的灰度任务逐一推进（S4-1） */
	public void scanGrayAdvance() {
		List<OtaTaskRow> tasks = taskMapper
			.selectList(new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getStatus, TASK_RUNNING)
				.eq(OtaTaskRow::getTaskType, 3));
		for (OtaTaskRow task : tasks) {
			try {
				advanceGray(task.getTaskId());
			}
			catch (Exception e) {
				log.warn("[OTA] 灰度推进失败 taskId={}", task.getTaskId(), e);
			}
		}
	}

	/**
	 * 计划开始扫描（OtaScheduler 每分钟调用，无租户上下文）：
	 * 对「待开始且已设计划时间且已到点」的任务，自动开始（用户选定的开始时间到点即触发，不受其他约束）。
	 * <p>
	 * 调度线程无租户上下文，故对每条任务先注入其租户身份再调用 {@link #start(Long)}， 保证事务内租户过滤与下发链路（Feign/消息）
	 * 正确；完成后清理上下文。
	 * </p>
	 */
	public void scanScheduledStart() {
		List<OtaTaskRow> pending = taskMapper
			.selectList(new LambdaQueryWrapper<OtaTaskRow>().eq(OtaTaskRow::getStatus, TASK_PENDING)
				.isNotNull(OtaTaskRow::getScheduleTime));
		LocalDateTime now = LocalDateTime.now();
		for (OtaTaskRow task : pending) {
			// 计划时间未到 → 跳过（到点后下一分钟扫描触发）
			if (task.getScheduleTime().isAfter(now)) {
				continue;
			}
			TenantContext.set(new TenantInfo(task.getTenantId(), null));
			try {
				start(task.getTaskId());
				log.info("[OTA] 计划任务到点自动开始 taskId={}", task.getTaskId());
			}
			catch (Exception e) {
				log.error("[OTA] 计划任务自动开始失败 taskId={}", task.getTaskId(), e);
			}
			finally {
				TenantContext.clear();
			}
		}
	}

	/**
	 * 事务提交后下发设备升级通知。
	 * <p>
	 * start 事务内只做任务状态置 RUNNING；设备下发（含 Feign 查差分/设备名、MQ 发布）延迟到此处， 避免事务内远程调用持有 DB
	 * 连接，也保证仅当状态落库成功后才真正下发。
	 * </p>
	 * @param taskId 已启动任务 ID（提交后已生成）
	 */
	public void onTaskStartedAfterCommit(Long taskId) {
		OtaTaskRow task = taskMapper.selectById(taskId);
		if (task == null || task.getStatus() != TASK_RUNNING) {
			return;
		}
		dispatchPending(task);
	}

}
