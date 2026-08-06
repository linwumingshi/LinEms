package com.sanduo.energy.shadow.web;

import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.shadow.service.ShadowService;
import com.sanduo.energy.shadow.web.dto.DesiredRequest;
import com.sanduo.energy.shadow.web.dto.ShadowView;
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
 *   <li>GET /api/shadow/{deviceId}        影子合并视图（Redis 热路径，未命中回 MySQL）；</li>
 *   <li>PUT /api/shadow/{deviceId}/desired 设置期望值 → 双写 desired + delta 发布。</li>
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

    @GetMapping("/{deviceId}")
    public Result<ShadowView> getShadow(@PathVariable long deviceId) {
        return Result.ok(shadowService.getShadow(deviceId));
    }

    @PutMapping("/{deviceId}/desired")
    public Result<ShadowService.DesiredResult> setDesired(@PathVariable long deviceId,
                                                          @Valid @RequestBody DesiredRequest request) {
        ShadowService.DesiredResult result = shadowService.setDesired(deviceId, 0L, request.getDesired());
        log.info("[Shadow] 设置 desired deviceId={} desiredKeys={} deltaKeys={}",
                deviceId, request.getDesired().size(), result.delta().size());
        return Result.ok(result);
    }
}
