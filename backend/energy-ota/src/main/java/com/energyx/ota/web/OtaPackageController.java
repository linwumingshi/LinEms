package com.energyx.ota.web;

import com.energyx.common.model.Result;
import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.entity.OtaPackageRow;
import com.energyx.ota.service.OtaPackageService;
import com.energyx.ota.web.dto.OtaPackageSaveReq;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OTA 升级包 API（网关路由 /api/ota/** → energy-ota）。
 *
 * <ul>
 * <li>POST /api/ota/packages 上传升级包（multipart）；</li>
 * <li>GET /api/ota/packages 分页列表；GET /api/ota/packages/{id} 详情；</li>
 * <li>PUT /api/ota/packages/{id}/status 停用/启用；DELETE /api/ota/packages/{id} 删除；</li>
 * <li>GET /api/ota/files/** 升级包文件下载（静态服务，设备 HTTPS/Range 分片下载基础）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ota")
public class OtaPackageController {

	private final OtaPackageService packageService;

	private final OtaProperties props;

	public OtaPackageController(OtaPackageService packageService, OtaProperties props) {
		this.packageService = packageService;
		this.props = props;
	}

	@PostMapping(value = "/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Result<Long> upload(@RequestPart("file") MultipartFile file, OtaPackageSaveReq req) {
		return Result.ok(packageService.upload(file, req));
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
	 * 升级包文件下载（支持 HTTP Range 请求头，设备分片/断点续传基础）。
	 * 路径：/api/ota/files/{productKey}/{version}/{module}/{fileName}
	 */
	@GetMapping("/files/**")
	public ResponseEntity<byte[]> download(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String prefix = "/api/ota/files/";
		if (!uri.startsWith(prefix)) {
			return ResponseEntity.notFound().build();
		}
		String rel = uri.substring(prefix.length());
		Path root = Paths.get(props.getStorageDir()).toAbsolutePath().normalize();
		Path file = root.resolve(rel).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file)) {
			return ResponseEntity.notFound().build();
		}
		try {
			byte[] data = Files.readAllBytes(file);
			return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(data);
		}
		catch (Exception e) {
			return ResponseEntity.internalServerError().build();
		}
	}

}
