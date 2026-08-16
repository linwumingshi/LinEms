package com.energyx.ota.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.model.Result;
import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.entity.OtaPackageRow;
import com.energyx.ota.security.OtaJwtAuth;
import com.energyx.ota.service.OtaPackageService;
import com.energyx.ota.service.OtaUrlSignService;
import com.energyx.ota.storage.StorageService;
import com.energyx.ota.web.dto.OtaPackageSaveReq;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OTA 升级包 API（网关路由 /api/ota/** → energy-ota）。
 *
 * <ul>
 * <li>POST /api/ota/packages 上传升级包（multipart，自动 RSA 签名）；</li>
 * <li>POST /api/ota/packages/{id}/diff?basePackageId= 平台生成差分包（S5-1）；</li>
 * <li>GET /api/ota/packages/{id}/verify-signature 验签（S5-2）；</li>
 * <li>GET /api/ota/files/** 升级包文件下载——签名 URL 校验（S5-4）+ HTTP Range 分片/断点续传（206 Partial
 * Content）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ota")
public class OtaPackageController {

	private final OtaPackageService packageService;

	private final OtaUrlSignService urlSignService;

	private final StorageService storageService;

	private final OtaProperties props;

	private final OtaJwtAuth otaJwtAuth;

	public OtaPackageController(OtaPackageService packageService, OtaUrlSignService urlSignService,
			StorageService storageService, OtaProperties props, OtaJwtAuth otaJwtAuth) {
		this.packageService = packageService;
		this.urlSignService = urlSignService;
		this.storageService = storageService;
		this.props = props;
		this.otaJwtAuth = otaJwtAuth;
	}

	@PostMapping(value = "/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Result<Long> upload(@RequestPart("file") MultipartFile file, OtaPackageSaveReq req) {
		return Result.ok(packageService.upload(file, req));
	}

	/** 平台生成差分包（basePackageId 源全量包 → 本包目标全量包） */
	@PostMapping("/packages/{packageId}/diff")
	public Result<Object> generateDiff(@PathVariable Long packageId, @RequestParam Long basePackageId) {
		Long diffId = packageService.generateDiff(packageId, basePackageId);
		if (diffId == null) {
			return Result.ok(Map.of("diffPackageId", "", "message", "差分无收益，已退化为全量下发"));
		}
		return Result.ok(Map.of("diffPackageId", diffId, "message", "差分包已生成"));
	}

	/** 验签：文件 SHA256 + 签名是否匹配公钥 */
	@GetMapping("/packages/{packageId}/verify-signature")
	public Result<Boolean> verifySignature(@PathVariable Long packageId) {
		return Result.ok(packageService.verifySignature(packageId));
	}

	/** 生成签名下载 URL（管理端/下发信封预生成） */
	@GetMapping("/packages/{packageId}/url")
	public Result<Map<String, Object>> signedUrl(@PathVariable Long packageId) {
		OtaPackageRow row = packageService.get(packageId);
		return Result.ok(Map.of("url", urlSignService.signUrl(row.getFilePath()), "expires",
				props.getUrlExpireSeconds(), "signMethod", "HMAC-SHA256", "sha256", row.getSha256(), "signature",
				row.getSignature() == null ? "" : row.getSignature()));
	}

	@GetMapping("/packages")
	public Result<Object> page(@RequestParam(required = false) String productKey,
			@RequestParam(required = false) String version, @RequestParam(required = false) Integer packageType,
			@RequestParam(required = false) Integer status, @RequestParam(defaultValue = "1") long pageNum,
			@RequestParam(defaultValue = "10") long pageSize) {
		return Result.ok(packageService.page(productKey, version, packageType, status, pageNum, pageSize));
	}

	@GetMapping("/packages/{packageId}")
	public Result<OtaPackageRow> get(@PathVariable Long packageId) {
		return Result.ok(packageService.get(packageId));
	}

	@PutMapping("/packages/{packageId}/status")
	public Result<Void> updateStatus(@PathVariable Long packageId, @RequestParam int status) {
		packageService.updateStatus(packageId, status);
		return Result.ok();
	}

	@DeleteMapping("/packages/{packageId}")
	public Result<Void> delete(@PathVariable Long packageId) {
		packageService.delete(packageId);
		return Result.ok();
	}

	/**
	 * 升级包文件下载——双模式鉴权 + HTTP Range 分片/断点续传。
	 * 路径：/api/ota/files/{productKey}/{version}/{module}/{fileName}[?expires=&sign=]
	 * <ul>
	 * <li>设备/信封模式：携带合法 {@code expires+sign} 签名 URL（S5-4），无需登录；过期/篡改 → 403；</li>
	 * <li>管理端模式：无签名参数时要求合法登录态 JWT（与网关同源验签），未登录/无效 → 401；</li>
	 * <li>带 Range 头 → 206 Partial Content（分片断点续传，块大小 segmentSize）；</li>
	 * <li>普通 GET → 200 全量。</li>
	 * </ul>
	 */
	@GetMapping("/files/**")
	public ResponseEntity<byte[]> download(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String prefix = "/api/ota/files/";
		if (!uri.startsWith(prefix)) {
			return ResponseEntity.notFound().build();
		}
		String objectKey = uri.substring(prefix.length());

		// 双模式鉴权：签名 URL（设备）优先，其次管理端登录态 JWT
		String expiresStr = request.getParameter("expires");
		String sign = request.getParameter("sign");
		if (StringUtils.hasText(expiresStr) && sign != null) {
			// 设备/信封模式：校验签名 URL（防篡改 + 时效）
			long expires;
			try {
				expires = Long.parseLong(expiresStr.trim());
			}
			catch (NumberFormatException e) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).body("缺少或非法签名参数".getBytes(StandardCharsets.UTF_8));
			}
			if (!urlSignService.verify(objectKey, expires, sign)) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body("下载链接已过期或签名无效".getBytes(StandardCharsets.UTF_8));
			}
		}
		else {
			// 管理端模式：要求合法登录态（前端浏览器下载走此分支）
			Long tokenTenant;
			try {
				tokenTenant = otaJwtAuth.requireAuthenticatedTenant(request);
			}
			catch (BusinessException e) {
				// 未登录/失效：返回 401（不走全局异常处理器包成 200+JSON，否则前端会把错误体当固件文件下载）
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(("下载需登录：" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
			}
			// 固件归属租户校验（防越权下载其他租户固件）：按 filePath 精确匹配，绕过租户插件避免误判
			Long ownerTenant = packageService.findTenantIdByFilePathUnfiltered(objectKey);
			if (ownerTenant != null && !ownerTenant.equals(tokenTenant)) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权下载该租户的固件".getBytes(StandardCharsets.UTF_8));
			}
		}

		if (!storageService.exists(objectKey)) {
			return ResponseEntity.notFound().build();
		}
		long total = storageService.size(objectKey);
		String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);

		// Range 分片（断点续传）：bytes=start-end / bytes=start-（到结尾）
		String range = request.getHeader(HttpHeaders.RANGE);
		if (range != null && range.startsWith("bytes=")) {
			try {
				String spec = range.substring(6).trim();
				long start = Long.parseLong(spec.split("-")[0].trim());
				if (start < 0 || start >= total) {
					return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
						.header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
						.build();
				}
				long end = -1;
				String[] parts = spec.split("-");
				if (parts.length > 1 && !parts[1].trim().isEmpty()) {
					end = Long.parseLong(parts[1].trim());
				}
				long last = (end >= 0 && end < total) ? end : total - 1;
				int length = (int) Math.min(props.getSegmentSize(), last - start + 1);
				byte[] data = storageService.readRange(objectKey, start, length);
				return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
					.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + (start + data.length - 1) + "/" + total)
					.header(HttpHeaders.ACCEPT_RANGES, "bytes")
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.body(data);
			}
			catch (Exception e) {
				return ResponseEntity.badRequest().build();
			}
		}
		// 全量下载
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
			.header(HttpHeaders.ACCEPT_RANGES, "bytes")
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(storageService.readBytes(objectKey));
	}

}
