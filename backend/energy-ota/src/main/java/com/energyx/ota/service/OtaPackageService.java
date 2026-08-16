package com.energyx.ota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.PageResult;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.entity.OtaPackageRow;
import com.energyx.ota.mapper.OtaPackageMapper;
import com.energyx.ota.web.dto.OtaPackageSaveReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * OTA 升级包管理：文件存储（本地目录）+ MD5/SHA256 校验 + CRUD。
 *
 * <p>
 * 升级包同表承载全量包与差分包（packageType/baseVersion）；文件按
 * {@code {storageDir}/{productKey}/{version}/{fileName}} 落盘，元数据入库。
 * </p>
 */
@Slf4j
@Service
public class OtaPackageService {

	private final OtaPackageMapper packageMapper;

	private final OtaProperties props;

	public OtaPackageService(OtaPackageMapper packageMapper, OtaProperties props) {
		this.packageMapper = packageMapper;
		this.props = props;
	}

	/**
	 * 上传升级包：校验元数据 + 落盘 + 计算 MD5/SHA256 + 入库。
	 * @param file 固件文件
	 * @param req 升级包元数据（产品/版本/类型/源版本等）
	 * @return 升级包 ID
	 */
	@Transactional
	public Long upload(MultipartFile file, OtaPackageSaveReq req) {
		Long tenantId = requireTenant();
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "固件文件不能为空");
		}
		if (!StringUtils.hasText(req.getProductKey()) || !StringUtils.hasText(req.getVersion())) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "产品标识与固件版本必填");
		}
		int packageType = req.getPackageType() == null ? 1 : req.getPackageType();
		if (packageType == 2 && !StringUtils.hasText(req.getBaseVersion())) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "差分包必须指定差分源版本 baseVersion");
		}
		// 同租户同产品同版本同模块（全量/差分）不允许重复上传
		Long exist = packageMapper
			.selectCount(new LambdaQueryWrapper<OtaPackageRow>().eq(OtaPackageRow::getTenantId, tenantId)
				.eq(OtaPackageRow::getProductKey, req.getProductKey())
				.eq(OtaPackageRow::getVersion, req.getVersion())
				.eq(OtaPackageRow::getModule, req.getModule() == null ? "main" : req.getModule())
				.eq(packageType == 2, OtaPackageRow::getBaseVersion, req.getBaseVersion()));
		if (exist != null && exist > 0) {
			throw new BusinessException(ErrorCode.CONFLICT,
					"该产品版本已存在升级包" + (packageType == 2 ? "（源版本 " + req.getBaseVersion() + "）" : ""));
		}

		String productKey = req.getProductKey();
		String version = req.getVersion();
		String module = req.getModule() == null ? "main" : req.getModule();
		String fileName = file.getOriginalFilename() == null ? productKey + "-" + version + ".bin"
				: file.getOriginalFilename();

		try {
			Path dir = Paths.get(props.getStorageDir(), productKey, version, module).toAbsolutePath().normalize();
			Files.createDirectories(dir);
			Path target = dir.resolve(fileName).normalize();
			// 防路径穿越：目标文件必须仍在 storageDir 内
			if (!target.startsWith(Paths.get(props.getStorageDir()).toAbsolutePath().normalize())) {
				throw new BusinessException(ErrorCode.PARAM_INVALID, "非法文件名");
			}
			file.transferTo(target);

			OtaPackageRow row = new OtaPackageRow();
			row.setTenantId(tenantId);
			row.setProductKey(productKey);
			row.setVersion(version);
			row.setModule(module);
			row.setPackageType(packageType);
			row.setBaseVersion(packageType == 2 ? req.getBaseVersion() : null);
			row.setFileName(fileName);
			row.setFilePath(props.getStorageDir() + "/" + productKey + "/" + version + "/" + module + "/" + fileName);
			row.setFileSize(file.getSize());
			row.setMd5(digest(file, "MD5"));
			row.setSha256(digest(file, "SHA-256"));
			row.setSourceVersions(req.getSourceVersions());
			row.setDescription(req.getDescription());
			row.setStatus(1);
			row.setCreateBy(req.getCreateBy() == null ? 0L : req.getCreateBy());
			packageMapper.insert(row);
			log.info("[OTA] 升级包上传成功 productKey={} version={} type={} size={} packageId={}", productKey, version,
					packageType, file.getSize(), row.getPackageId());
			return row.getPackageId();
		}
		catch (IOException e) {
			log.error("[OTA] 升级包落盘失败 productKey={} version={}", productKey, version, e);
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "升级包文件存储失败");
		}
	}

	/** 分页查询升级包（产品/版本/类型/状态过滤，按创建时间倒序） */
	public PageResult<OtaPackageRow> page(String productKey, String version, Integer packageType, Integer status,
			long pageNum, long pageSize) {
		Long tenantId = requireTenant();
		Page<OtaPackageRow> p = packageMapper.selectPage(new Page<>(pageNum, pageSize),
				new LambdaQueryWrapper<OtaPackageRow>().eq(OtaPackageRow::getTenantId, tenantId)
					.eq(StringUtils.hasText(productKey), OtaPackageRow::getProductKey, productKey)
					.eq(StringUtils.hasText(version), OtaPackageRow::getVersion, version)
					.eq(packageType != null, OtaPackageRow::getPackageType, packageType)
					.eq(status != null, OtaPackageRow::getStatus, status)
					.orderByDesc(OtaPackageRow::getCreateTime));
		return PageResult.of(p);
	}

	/** 升级包详情 */
	public OtaPackageRow get(Long packageId) {
		OtaPackageRow row = packageMapper.selectById(packageId);
		if (row == null || !row.getTenantId().equals(requireTenant())) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "升级包不存在");
		}
		return row;
	}

	/** 停用/启用升级包（停用后新任务不可引用，进行中任务不受影响） */
	public void updateStatus(Long packageId, int status) {
		if (status != 1 && status != 2) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "非法状态");
		}
		OtaPackageRow row = get(packageId);
		row.setStatus(status);
		packageMapper.updateById(row);
	}

	/** 删除升级包（逻辑删；S3 任务引用校验后放开删除） */
	@Transactional
	public void delete(Long packageId) {
		get(packageId);
		packageMapper.deleteById(packageId);
	}

	/** 按产品 + 目标版本 + 源版本查差分升级包（S5 差分下发使用） */
	public OtaPackageRow findDiff(Long tenantId, String productKey, String version, String baseVersion) {
		return packageMapper.selectOne(new LambdaQueryWrapper<OtaPackageRow>().eq(OtaPackageRow::getTenantId, tenantId)
			.eq(OtaPackageRow::getProductKey, productKey)
			.eq(OtaPackageRow::getVersion, version)
			.eq(OtaPackageRow::getPackageType, 2)
			.eq(OtaPackageRow::getBaseVersion, baseVersion)
			.eq(OtaPackageRow::getStatus, 1)
			.last("LIMIT 1"));
	}

	/** 按产品 + 目标版本查全量升级包（下发兜底） */
	public OtaPackageRow findFull(Long tenantId, String productKey, String version) {
		return packageMapper.selectOne(new LambdaQueryWrapper<OtaPackageRow>().eq(OtaPackageRow::getTenantId, tenantId)
			.eq(OtaPackageRow::getProductKey, productKey)
			.eq(OtaPackageRow::getVersion, version)
			.eq(OtaPackageRow::getPackageType, 1)
			.eq(OtaPackageRow::getStatus, 1)
			.last("LIMIT 1"));
	}

	/** 计算文件摘要（MD5/SHA-256） */
	private String digest(MultipartFile file, String algorithm) {
		try (InputStream in = file.getInputStream()) {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
			return HexFormat.of().formatHex(md.digest());
		}
		catch (IOException | NoSuchAlgorithmException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件摘要计算失败");
		}
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
