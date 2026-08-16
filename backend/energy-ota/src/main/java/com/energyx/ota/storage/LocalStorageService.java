package com.energyx.ota.storage;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.ota.config.OtaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地目录文件存储实现（storage-type=local，默认）。
 *
 * <p>
 * 文件落在 {@code {storageDir}/{objectKey}}；防路径穿越：解析后的绝对路径必须以 storageDir 为前缀，否则拒绝。
 * </p>
 */
@Slf4j
@Component
public class LocalStorageService implements StorageService {

	private final OtaProperties props;

	public LocalStorageService(OtaProperties props) {
		this.props = props;
	}

	@Override
	public String type() {
		return "local";
	}

	@Override
	public void store(String objectKey, InputStream in, long size) {
		try {
			Path target = resolve(objectKey);
			Files.createDirectories(target.getParent());
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e) {
			log.error("[OTA] 文件存储失败 objectKey={}", objectKey, e);
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "升级包文件存储失败");
		}
	}

	@Override
	public InputStream open(String objectKey) {
		try {
			return Files.newInputStream(resolve(objectKey));
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "升级包文件不存在");
		}
	}

	@Override
	public byte[] readBytes(String objectKey) {
		try {
			return Files.readAllBytes(resolve(objectKey));
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "升级包文件不存在");
		}
	}

	@Override
	public void delete(String objectKey) {
		try {
			Files.deleteIfExists(resolve(objectKey));
		}
		catch (IOException e) {
			log.warn("[OTA] 文件删除失败 objectKey={}", objectKey, e);
		}
	}

	@Override
	public boolean exists(String objectKey) {
		return Files.isRegularFile(resolve(objectKey));
	}

	@Override
	public byte[] readRange(String objectKey, long offset, int length) {
		try {
			Path p = resolve(objectKey);
			long total = Files.size(p);
			if (offset < 0 || offset >= total) {
				return new byte[0];
			}
			int len = length <= 0 ? (int) (total - offset) : Math.min(length, (int) (total - offset));
			try (InputStream in = Files.newInputStream(p)) {
				in.skipNBytes(offset);
				return in.readNBytes(len);
			}
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "升级包文件不存在");
		}
	}

	@Override
	public long size(String objectKey) {
		try {
			return Files.size(resolve(objectKey));
		}
		catch (IOException e) {
			return -1;
		}
	}

	/** 相对路径 → 绝对路径（防穿越：必须在 storageDir 内） */
	private Path resolve(String objectKey) {
		Path root = Paths.get(props.getStorageDir()).toAbsolutePath().normalize();
		Path target = root.resolve(objectKey).normalize();
		if (!target.startsWith(root)) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "非法文件路径");
		}
		return target;
	}

}
