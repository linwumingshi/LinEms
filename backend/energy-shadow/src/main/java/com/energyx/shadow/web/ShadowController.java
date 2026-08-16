package com.energyx.shadow.web;

import com.energyx.common.model.Result;
import com.energyx.common.tenant.TenantContext;
import com.energyx.shadow.service.ShadowService;
import com.energyx.shadow.web.dto.DesiredRequest;
import com.energyx.shadow.web.dto.ShadowView;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 影子查询/设置 API。
 *
 * <ul>
 * <li>GET /api/shadow/{deviceId} 影子合并视图（Redis 热路径，未命中回 MySQL）；</li>
 * <li>PUT /api/shadow/{deviceId}/desired 设置期望值 → 双写 desired + delta 发布。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/shadow")
public class ShadowController {

	private final ShadowService shadowService;

	public ShadowController(ShadowService shadowService) {
		this.shadowService = shadowService;
	}

	/**
	 * 查询设备影子合并视图。Redis 热路径优先，未命中回查 MySQL；返回 reported/desired 快照、 乐观锁版本与最后上报时间。
	 * @param deviceId 设备 ID（路径变量）
	 * @return {@link Result}<{@link ShadowView}> 影子合并视图
	 */
	@GetMapping("/{deviceId}")
	public Result<ShadowView> getShadow(@PathVariable long deviceId) {
		return Result.ok(shadowService.getShadow(deviceId));
	}

	/**
	 * 设置设备影子期望值。双写 Redis desired 与 MySQL，并检测与当前 reported 的差异（delta）发布到 iot-shadow-delta
	 * 供设备同步。租户取自请求上下文（网关 x-tenant-id 透传），无上下文兜底 0 防止越权。
	 * @param deviceId 设备 ID（路径变量）
	 * @param request 请求体，字段说明见 {@link DesiredRequest}
	 * @return {@link Result}<{@link ShadowService.DesiredResult}> 期望值集合与差异集合
	 */
	@PutMapping("/{deviceId}/desired")
	public Result<ShadowService.DesiredResult> setDesired(@PathVariable long deviceId,
			@Valid @RequestBody DesiredRequest request) {
		// 租户取当前请求上下文（经网关 x-tenant-id 透传）；无上下文兜底 0，避免越权写他人租户
		Long tenantId = TenantContext.getTenantId();
		ShadowService.DesiredResult result = shadowService.setDesired(deviceId, tenantId == null ? 0L : tenantId,
				request.getDesired());
		log.info("[Shadow] 设置 desired deviceId={} desiredKeys={} deltaKeys={}", deviceId, request.getDesired().size(),
				result.delta().size());
		return Result.ok(result);
	}

}
