package com.energyx.device.web;

import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.device.entity.Device;
import com.energyx.device.service.DeviceService;
import com.energyx.device.web.dto.CredentialView;
import com.energyx.device.web.dto.DeviceCreateReq;
import com.energyx.device.web.dto.DeviceQuery;
import com.energyx.device.web.dto.DeviceUpdateReq;
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
 * <li>POST /api/device 创建设备（根或挂子节点，生成密钥）；</li>
 * <li>GET /api/device/page 分页查询；</li>
 * <li>GET /api/device/tree?rootId= 资产树（rootId 空 = 全量）；</li>
 * <li>GET /api/device/by-name?productKey=&amp;deviceName= 按 productKey+deviceName
 * 查单设备；</li>
 * <li>GET /api/device/list-by-station?tenantId=&amp;stationId= 按电站+类型查设备列表；</li>
 * <li>GET /api/device/{id} 设备详情；</li>
 * <li>PUT /api/device/{id} 更新；</li>
 * <li>POST /api/device/{id}/activate 激活；</li>
 * <li>POST /api/device/{id}/disable 禁用；</li>
 * <li>POST /api/device/{id}/enable 启用；</li>
 * <li>DELETE /api/device/{id} 逻辑删除子树 + 吊销凭据；</li>
 * <li>GET /api/device/{id}/credential 凭据视图（脱敏）；</li>
 * <li>POST /api/device/{id}/credential/regenerate 重新生成密钥。</li>
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

	/**
	 * 创建设备。
	 * <p>
	 * 创建根设备（parentId 空或 0）或挂在指定父节点下，统一生成设备树层级与物化路径，并生成 HMAC 认证密钥。
	 * </p>
	 * @param req 请求体，字段说明见 {@link DeviceCreateReq}
	 * @return {@link Result}&lt;{@link Long}&gt; 新建设备的主键 deviceId
	 */
	@PostMapping
	public Result<Long> create(@Valid @RequestBody DeviceCreateReq req) {
		return Result.ok(deviceService.create(req));
	}

	/**
	 * 分页查询设备列表。
	 * <p>
	 * 按站点、设备类型、父节点、状态、产品标识、设备名关键字等条件筛选；租户范围由上下文自动注入。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link DeviceQuery}
	 * @return {@link Result}&lt;{@link PageResult}&lt;{@link Device}&gt;&gt; 分页设备列表
	 */
	@GetMapping("/page")
	public Result<PageResult<Device>> page(DeviceQuery query) {
		return Result.ok(PageResult.of(deviceService.page(query)));
	}

	/**
	 * 查询资产树。
	 * <p>
	 * 以 rootId 为根返回其下整棵设备树（含 {@code children} 子节点）；rootId 为空则返回全量设备树。
	 * </p>
	 * @param rootId 子树根设备 ID（来源：查询参数，可选；空 = 全量）
	 * @return {@link Result}&lt;{@link List}&lt;{@link Device}&gt;&gt; 设备树列表（含 children
	 * 子树）
	 */
	@GetMapping("/tree")
	public Result<List<Device>> tree(@RequestParam(required = false) Long rootId) {
		return Result.ok(deviceService.tree(rootId));
	}

	/**
	 * 按 productKey + deviceName 查询单个设备。
	 * <p>
	 * 供跨服务调用方通过认证锚点（productKey/deviceName）反查设备，精确路径优先于 /{deviceId}。
	 * </p>
	 * @param productKey 产品标识（来源：查询参数）
	 * @param deviceName 设备名（来源：查询参数）
	 * @return {@link Result}&lt;{@link Device}&gt; 匹配的设备详情
	 */
	@GetMapping("/by-name")
	public Result<Device> byName(@RequestParam String productKey, @RequestParam String deviceName) {
		return Result.ok(deviceService.findByProductKeyAndName(productKey, deviceName));
	}

	/**
	 * 按电站 + 设备类型查询设备列表。
	 * <p>
	 * 供跨服务调用方解析下发目标（如按电站 + 类型批量定位设备）。
	 * </p>
	 * @param tenantId 租户 ID（来源：查询参数）
	 * @param stationId 电站 ID（来源：查询参数）
	 * @param productKey 产品标识（来源：查询参数，可选）
	 * @param deviceType 设备类型（来源：查询参数，可选；见 {@link com.energyx.common.enums.DeviceType}）
	 * @return {@link Result}&lt;{@link List}&lt;{@link Device}&gt;&gt; 匹配的设备列表
	 */
	@GetMapping("/list-by-station")
	public Result<List<Device>> listByStation(@RequestParam Long tenantId, @RequestParam Long stationId,
			@RequestParam(required = false) String productKey, @RequestParam(required = false) String deviceType) {
		return Result.ok(deviceService.listByStation(tenantId, stationId, productKey, deviceType));
	}

	/**
	 * 查询设备详情。
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link Device}&gt; 设备详情
	 */
	@GetMapping("/{deviceId}")
	public Result<Device> detail(@PathVariable Long deviceId) {
		return Result.ok(deviceService.detail(deviceId));
	}

	/**
	 * 更新设备。
	 * <p>
	 * 仅更新请求中非空字段（部分更新）。
	 * </p>
	 * @param deviceId 设备主键（来源：路径变量）
	 * @param req 请求体，字段说明见 {@link DeviceUpdateReq}
	 * @return {@link Result}&lt;{@link Void}&gt; 更新成功
	 */
	@PutMapping("/{deviceId}")
	public Result<Void> update(@PathVariable Long deviceId, @Valid @RequestBody DeviceUpdateReq req) {
		deviceService.update(deviceId, req);
		return Result.ok();
	}

	/**
	 * 激活设备。
	 * <p>
	 * 将设备状态推进为已激活/在线就绪；关联到凭据状态置为正常。
	 * </p>
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link Void}&gt; 激活成功
	 */
	@PostMapping("/{deviceId}/activate")
	public Result<Void> activate(@PathVariable Long deviceId) {
		deviceService.activate(deviceId);
		return Result.ok();
	}

	/**
	 * 禁用设备。
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link Void}&gt; 禁用成功
	 */
	@PostMapping("/{deviceId}/disable")
	public Result<Void> disable(@PathVariable Long deviceId) {
		deviceService.disable(deviceId);
		return Result.ok();
	}

	/**
	 * 启用设备。
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link Void}&gt; 启用成功
	 */
	@PostMapping("/{deviceId}/enable")
	public Result<Void> enable(@PathVariable Long deviceId) {
		deviceService.enable(deviceId);
		return Result.ok();
	}

	/**
	 * 逻辑删除设备。
	 * <p>
	 * 删除整棵子树并吊销其关联凭据（凭据置为 REVOKED）。
	 * </p>
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link Void}&gt; 删除成功
	 */
	@DeleteMapping("/{deviceId}")
	public Result<Void> delete(@PathVariable Long deviceId) {
		deviceService.delete(deviceId);
		return Result.ok();
	}

	/**
	 * 获取设备凭据视图。
	 * <p>
	 * 返回脱敏后的凭据（deviceSecret 不返回明文），字段说明见 {@link CredentialView}。
	 * </p>
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link CredentialView}&gt; 凭据视图（脱敏）
	 */
	@GetMapping("/{deviceId}/credential")
	public Result<CredentialView> getCredential(@PathVariable Long deviceId) {
		return Result.ok(deviceService.getCredential(deviceId));
	}

	/**
	 * 重新生成设备密钥。
	 * <p>
	 * 吊销旧密钥并生成新 deviceSecret，本次响应返回明文（创建/重生成时才会返回明文）。
	 * </p>
	 * @param deviceId 设备主键（来源：路径变量）
	 * @return {@link Result}&lt;{@link CredentialView}&gt; 新凭据视图（含明文 deviceSecret）
	 */
	@PostMapping("/{deviceId}/credential/regenerate")
	public Result<CredentialView> regenerate(@PathVariable Long deviceId) {
		return Result.ok(deviceService.regenerateSecret(deviceId));
	}

}
