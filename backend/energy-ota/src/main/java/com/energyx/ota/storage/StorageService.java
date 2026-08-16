package com.energyx.ota.storage;

import java.io.InputStream;

/**
 * OTA 升级包文件存储抽象（S5-3）：本地目录为默认实现，预留对象存储（OSS）适配。
 *
 * <p>
 * 接口按「相对路径（objectKey）」操作，业务侧不感知底层存储：
 * 上传（store）、读取（open/readBytes）、删除（delete）、是否存在（exists）。 objectKey
 * 约定：{productKey}/{version}/{module}/{fileName}。
 * </p>
 */
public interface StorageService {

	/** 存储类型标识（local/oss，对应配置 energy.ota.storage-type） */
	String type();

	/**
	 * 写入文件。
	 * @param objectKey 相对存储路径（如 snd_ess_pcs/1.0.0/main/a.bin）
	 * @param in 文件输入流（由调用方关闭）
	 * @param size 文件大小（对象存储分片上传预留）
	 */
	void store(String objectKey, InputStream in, long size);

	/** 打开文件输入流（调用方负责关闭） */
	InputStream open(String objectKey);

	/** 读取全部字节（小文件/校验用；大文件走分片 Range 请用 open） */
	byte[] readBytes(String objectKey);

	/** 删除文件（不存在不报错） */
	void delete(String objectKey);

	/** 文件是否存在 */
	boolean exists(String objectKey);

	/**
	 * 分片读取（Range 断点续传）。
	 * @param objectKey 相对路径
	 * @param offset 起始偏移（>=0）
	 * @param length 读取字节数（<=0 表示读到结尾）
	 * @return 分片字节（可能短于 length 当文件不足）
	 */
	byte[] readRange(String objectKey, long offset, int length);

	/** 文件大小（字节）；不存在返回 -1 */
	long size(String objectKey);

}
