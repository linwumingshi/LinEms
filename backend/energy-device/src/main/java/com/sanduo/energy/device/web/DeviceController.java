package com.sanduo.energy.device.web;

import com.sanduo.energy.common.model.PageResult;
import com.sanduo.energy.common.model.Result;
import com.sanduo.energy.device.entity.Device;
import com.sanduo.energy.device.service.DeviceService;
import com.sanduo.energy.device.web.dto.CredentialView;
import com.sanduo.energy.device.web.dto.DeviceCreateReq;
import com.sanduo.energy.device.web.dto.DeviceQuery;
import com.sanduo.energy.device.web.dto.DeviceUpdateReq;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备资产 API（统一设备树）。
 *
 * <ul>
 *   <li>POST   /api/device                          创建设备（根或挂子节点，生成密钥）；</li>
 *   <li>GET    /api/device/page                     分页查询；</li>
 *   <li>GET    /api/device/tree?rootId=             资产树（rootId 空 = 全量）；</li>
 *   <li>GET    /api/device/{id}                     设备详情；</li>
 *   <li>PUT    /api/device/{id}                     更新；</li>
 *   <li>DELETE /api/device/{id}                     逻辑删除子树 + 吊销凭据；</li>
 *   <li>GET    /api/device/{id}/credential          凭据视图（脱敏）；</li>
 *   <li>POST   /api/device/{id}/credential/regenerate 重新生成密钥。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody DeviceCreateReq req) {
        return Result.ok(deviceService.create(req));
    }

    @GetMapping("/page")
    public Result<PageResult<Device>> page(DeviceQuery query) {
        return Result.ok(PageResult.of(deviceService.page(query)));
    }

    @GetMapping("/tree")
    public Result<List<Device>> tree(@RequestParam(required = false) Long rootId) {
        return Result.ok(deviceService.tree(rootId));
    }

    @GetMapping("/{deviceId}")
    public Result<Device> detail(@PathVariable Long deviceId) {
        return Result.ok(deviceService.detail(deviceId));
    }

    @PutMapping("/{deviceId}")
    public Result<Void> update(@PathVariable Long deviceId, @Valid @RequestBody DeviceUpdateReq req) {
        deviceService.update(deviceId, req);
        return Result.ok();
    }

    @DeleteMapping("/{deviceId}")
    public Result<Void> delete(@PathVariable Long deviceId) {
        deviceService.delete(deviceId);
        return Result.ok();
    }

    @GetMapping("/{deviceId}/credential")
    public Result<CredentialView> getCredential(@PathVariable Long deviceId) {
        return Result.ok(deviceService.getCredential(deviceId));
    }

    @PostMapping("/{deviceId}/credential/regenerate")
    public Result<CredentialView> regenerate(@PathVariable Long deviceId) {
        return Result.ok(deviceService.regenerateSecret(deviceId));
    }
}
