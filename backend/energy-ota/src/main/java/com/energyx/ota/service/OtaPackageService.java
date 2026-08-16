package com.energyx.ota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.PageResult;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ota.config.OtaProperties;
import com.energyx.ota.entity.OtaPackageRow;
import com.energyx.ota.mapper.OtaPackageMapper;
import com.energyx.ota.storage.StorageService;
import com.energyx.ota.util.OtaCryptoUtil;
import com.energyx.ota.web.dto.OtaPackageSaveReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * OTA 升级包管理：文件存储（对象存储抽象）+ MD5/SHA256 校验 + RSA 签名 + 差分生成 + CRUD。
 *
 * <p>
 * 升级包同表承载全量包与差分包（packageType/baseVersion）；文件按 {@code {productKey}/{version}/{fileName}}
 * objectKey 落盘（local/oss 抽象）， 元数据入库。上传自动签名（S5-2），支持平台生成差分包（S5-1）。
 * </p>
 */
@Slf4j
@Service
public class OtaPackageService {

	private final OtaPackageMapper packageMapper;

	private final OtaProperties props;

	private final StorageService storageService;

	private final OtaSignService signService;

	private final OtaDiffService diffService;

	public OtaPackageService(OtaPackageMapper packageMapper, OtaProperties props, StorageService storageService,
			OtaSignService signService, OtaDiffService diffService) {
		this.packageMapper = packageMapper;
		this.props = props;
		this.storageService = storageService;
		this.signService = signService;
		this.diffService = diffService;
	}

	/**
	 * 上传升级包：校验元数据 + 落盘 + 计算 MD5/SHA256 + RSA 签名 + 入库。
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
		String objectKey = objectKey(productKey, version, module, fileName);

		try {
			byte[] fileBytes = file.getBytes();
			storageService.store(objectKey, new java.io.ByteArrayInputStream(fileBytes), fileBytes.length);
			String sha256 = OtaCryptoUtil.sha256(fileBytes);
			String md5 = digestHex(new java.io.ByteArrayInputStream(fileBytes), "MD5");

			OtaPackageRow row = new OtaPackageRow();
			row.setTenantId(tenantId);
			row.setProductKey(productKey);
			row.setVersion(version);
			row.setModule(module);
			row.setPackageType(packageType);
			row.setBaseVersion(packageType == 2 ? req.getBaseVersion() : null);
			row.setFileName(fileName);
			row.setFilePath(objectKey);
			row.setFileSize((long) fileBytes.length);
			row.setMd5(md5);
			row.setSha256(sha256);
			// S5-2：对文件 SHA256 摘要签名（signature 字段），设备侧用公钥验签
			row.setSignature(signService.signSha256(sha256));
			row.setSourceVersions(req.getSourceVersions());
			row.setDescription(req.getDescription());
			row.setStatus(1);
			row.setCreateBy(req.getCreateBy() == null ? 0L : req.getCreateBy());
			packageMapper.insert(row);
			log.info("[OTA] 升级包上传成功 productKey={} version={} type={} size={} packageId={}", productKey, version,
					packageType, fileBytes.length, row.getPackageId());
			return row.getPackageId();
		}
		catch (IOException e) {
			log.error("[OTA] 升级包落盘失败 productKey={} version={}", productKey, version, e);
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "升级包文件存储失败");
		}
	}

	/**
	 * 平台生成差分包（S5-1）：basePackageId（源全量包）+ 本包（目标全量包）→ 生成差分包入库。
	 * @param packageId 目标全量包 ID
	 * @param basePackageId 源全量包 ID
	 * @return 差分包 ID（null=差分无收益，退化为全量）
	 */
	@Transactional
	public Long generateDiff(Long packageId, Long basePackageId) {
		Long tenantId = requireTenant();
		OtaPackageRow target = get(packageId);
		OtaPackageRow base = packageMapper.selectById(basePackageId);
		if (base == null || !base.getTenantId().equals(tenantId) || base.getPackageType() != 1) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "源升级包不存在（须为全量包）");
		}
		if (target.getPackageType() != 1) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "目标包须为全量包");
		}
		byte[] baseBytes = storageService.readBytes(base.getFilePath());
		byte[] targetBytes = storageService.readBytes(target.getFilePath());
		byte[] diff = diffService.generate(baseBytes, targetBytes, props.getDiffBlockSize());
		if (!diffService.isSmallerThan(diff, targetBytes)) {
			log.info("[OTA] 差分无收益（diff {} >= target {}），退化为全量下发", diff.length, targetBytes.length);
			return null;
		}
		String diffFileName = target.getProductKey() + "-" + target.getVersion() + "-diff-" + base.getVersion()
				+ ".bin";
		String diffObjectKey = objectKey(target.getProductKey(), target.getVersion(), target.getModule(), diffFileName);
		diffService.storeDiff(diffObjectKey, diff);

		OtaPackageRow row = new OtaPackageRow();
		row.setTenantId(tenantId);
		row.setProductKey(target.getProductKey());
		row.setVersion(target.getVersion());
		row.setModule(target.getModule());
		row.setPackageType(2);
		row.setBaseVersion(base.getVersion());
		row.setFileName(diffFileName);
		row.setFilePath(diffObjectKey);
		row.setFileSize((long) diff.length);
		row.setMd5(OtaCryptoUtil.md5(diff));
		row.setSha256(diffService.diffSha256(diff));
		row.setSignature(signService.signSha256(row.getSha256()));
		row.setSourceVersions(base.getVersion());
		row.setDescription("平台生成差分包 " + base.getVersion() + " → " + target.getVersion());
		row.setStatus(1);
		row.setCreateBy(0L);
		packageMapper.insert(row);
		log.info("[OTA] 差分包生成成功 packageId={} base={} diffPackageId={} size={}", packageId, base.getVersion(),
				row.getPackageId(), diff.length);
		return row.getPackageId();
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

	/** 删除升级包（逻辑删 + 物理文件删除） */
	@Transactional
	public void delete(Long packageId) {
		OtaPackageRow row = get(packageId);
		packageMapper.deleteById(packageId);
		storageService.delete(row.getFilePath());
	}

	/** 验签（S5-2）：文件 SHA256 + 签名 是否与公钥匹配 */
	public boolean verifySignature(Long packageId) {
		OtaPackageRow row = get(packageId);
		return signService.verifySha256(row.getSha256(), row.getSignature());
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

	/** objectKey 构造（存储相对路径） */
	public String objectKey(String productKey, String version, String module, String fileName) {
		return productKey + "/" + version + "/" + module + "/" + fileName;
	}

	/**
	 * 按文件相对路径精确查询归属租户 ID（下载接口越权校验用）。
	 *
	 * <p>
	 * 临时清除租户上下文以绕过 MyBatis-Plus 租户插件，确保按 filePath 精确匹配—— 否则插件会附加 {@code tenant_id = 当前租户}
	 * 条件，导致跨租户文件查不到而被误判为「无主」放行。
	 * </p>
	 * @param objectKey 存储相对路径（{productKey}/{version}/{module}/{fileName}）
	 * @return 归属租户 ID；文件无对应元数据记录时返回 null
	 */
	public Long findTenantIdByFilePathUnfiltered(String objectKey) {
		Long savedTenant = TenantContext.getTenantId();
		Long savedEnterprise = TenantContext.getEnterpriseId();
		boolean hadTenant = TenantContext.hasTenant();
		TenantContext.clear();
		try {
			OtaPackageRow row = packageMapper.selectOne(
					new LambdaQueryWrapper<OtaPackageRow>().eq(OtaPackageRow::getFilePath, objectKey).last("LIMIT 1"));
			return row == null ? null : row.getTenantId();
		}
		finally {
			if (hadTenant) {
				TenantContext.set(new TenantInfo(savedTenant, savedEnterprise));
			}
		}
	}

	/** 文件绝对路径（本地实现需要；oss 时仅日志用） */
	public Path resolvePath(String objectKey) {
		return Paths.get(props.getStorageDir()).toAbsolutePath().normalize().resolve(objectKey).normalize();
	}

	/** 计算文件摘要（hex） */
	private String digestHex(InputStream in, String algorithm) {
		try {
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
