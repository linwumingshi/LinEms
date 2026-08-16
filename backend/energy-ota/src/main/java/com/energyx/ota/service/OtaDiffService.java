package com.energyx.ota.service;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.ota.storage.StorageService;
import com.energyx.ota.util.OtaCryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 块级差分包生成/合并（S5-1，bsdiff 简化实现）。
 *
 * <p>
 * 差分格式（自描述二进制，平台生成 / 设备端 bspatch 等价实现合并）： <pre>
 * magic(8B "EXDIFF01") + blockSize(4B) + blockCount(4B)
 * 对每块：flags(1B) —— 0=引用源块(不携带数据) 1=携带新块数据
 *         dataLen(4B) + data(引用块: 源块索引; 数据块: 原始字节)
 * </pre> 合并算法：目标块 = 引用 → 源文件对应块；数据 → 内联字节。合并产物 SHA256 必须等于全量包 sha256（targetSha256
 * 防差分算法缺陷/篡改）。
 * </p>
 */
@Slf4j
@Service
public class OtaDiffService {

	private static final byte[] MAGIC = "EXDIFF01".getBytes(StandardCharsets.US_ASCII);

	private final StorageService storageService;

	public OtaDiffService(StorageService storageService) {
		this.storageService = storageService;
	}

	/**
	 * 生成差分包字节：源文件（base）→ 目标文件（target），块级差分。
	 * @param baseBytes 源文件（旧版本全量包）
	 * @param targetBytes 目标文件（新版本全量包）
	 * @param blockSize 块大小（默认 4096）
	 * @return 差分包字节（可能大于 target 时调用方应退化为全量，见 {@link #isSmallerThan}）
	 */
	public byte[] generate(byte[] baseBytes, byte[] targetBytes, int blockSize) {
		int size = blockSize <= 0 ? 4096 : blockSize;
		int targetBlocks = (targetBytes.length + size - 1) / size;
		int baseBlocks = (baseBytes.length + size - 1) / size;
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(targetBytes.length / 2);
			DataOutputStream dos = new DataOutputStream(out);
			dos.write(MAGIC);
			dos.writeInt(size);
			dos.writeInt(targetBlocks);
			for (int i = 0; i < targetBlocks; i++) {
				int off = i * size;
				int len = Math.min(size, targetBytes.length - off);
				byte[] tBlock = Arrays.copyOfRange(targetBytes, off, off + len);
				int ref = findMatch(baseBytes, baseBlocks, size, tBlock);
				if (ref >= 0) {
					dos.writeByte(0); // 引用源块
					dos.writeInt(ref);
				}
				else {
					dos.writeByte(1); // 携带数据
					dos.writeInt(len);
					dos.write(tBlock);
				}
			}
			dos.flush();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "差分包生成失败");
		}
	}

	/** 在源文件中查找与目标块完全一致的块（返回块索引，无则 -1） */
	private int findMatch(byte[] base, int baseBlocks, int size, byte[] tBlock) {
		// 线性扫描（块数不多时足够；大批量可升级为哈希索引）
		for (int i = 0; i < baseBlocks; i++) {
			int off = i * size;
			int len = Math.min(size, base.length - off);
			if (len == tBlock.length && Arrays.equals(base, off, off + len, tBlock, 0, tBlock.length)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 合并差分包 → 目标文件字节。
	 * @param diffBytes 差分包
	 * @param baseBytes 源文件（旧版本全量包）
	 * @return 合并后的目标文件
	 */
	public byte[] patch(byte[] diffBytes, byte[] baseBytes) {
		try {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(diffBytes));
			byte[] magic = new byte[8];
			dis.readFully(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new BusinessException(ErrorCode.PARAM_INVALID, "差分包格式错误");
			}
			int size = dis.readInt();
			int blocks = dis.readInt();
			ByteArrayOutputStream out = new ByteArrayOutputStream(blocks * size);
			for (int i = 0; i < blocks; i++) {
				int flags = dis.readUnsignedByte();
				if (flags == 0) {
					int ref = dis.readInt();
					int off = ref * size;
					int len = Math.min(size, baseBytes.length - off);
					out.write(baseBytes, off, len);
				}
				else {
					int len = dis.readInt();
					byte[] data = new byte[len];
					dis.readFully(data);
					out.write(data);
				}
			}
			return out.toByteArray();
		}
		catch (EOFException e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "差分包截断");
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "差分包解析失败");
		}
	}

	/** 差分包是否比全量更小（否则无收益，调用方退化为全量下发） */
	public boolean isSmallerThan(byte[] diffBytes, byte[] targetBytes) {
		return diffBytes.length < targetBytes.length;
	}

	/** 差分包 SHA256 */
	public String diffSha256(byte[] diffBytes) {
		return OtaCryptoUtil.sha256(diffBytes);
	}

	/** 写差分文件到存储（objectKey 由调用方构造） */
	public void storeDiff(String objectKey, byte[] diffBytes) {
		storageService.store(objectKey, new ByteArrayInputStream(diffBytes), diffBytes.length);
	}

}
