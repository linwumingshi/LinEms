package com.energyx.common.model;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页响应包装，承载分页查询结果。
 *
 * <p>
 * 字段语义对齐 MyBatis-Plus {@link IPage}，便于将分页数据直接转换为统一响应体。
 * </p>
 *
 * @param <T> 当前页数据元素类型
 */
public class PageResult<T> implements Serializable {

	private static final long serialVersionUID = 1L;

	/** 总记录数 */
	private long total;

	/** 总页数 */
	private long pages;

	/** 当前页码（从 1 开始） */
	private long current;

	/** 每页大小（每页记录数） */
	private long size;

	/** 当前页数据列表 */
	private List<T> records;

	public PageResult() {
	}

	/**
	 * 由 MyBatis-Plus {@link IPage} 转换为分页响应体，逐字段拷贝。
	 * @param <T> 数据元素类型
	 * @param page MyBatis-Plus 分页结果
	 * @return 分页响应体
	 */
	public static <T> PageResult<T> of(IPage<T> page) {
		PageResult<T> result = new PageResult<>();
		result.setTotal(page.getTotal());
		result.setPages(page.getPages());
		result.setCurrent(page.getCurrent());
		result.setSize(page.getSize());
		result.setRecords(page.getRecords());
		return result;
	}

	/**
	 * 手动构造分页响应体，并按 {@code (total + size - 1) / size} 自动计算总页数（size 为 0 时总页数为 0）。
	 * @param <T> 数据元素类型
	 * @param total 总记录数
	 * @param current 当前页码
	 * @param size 每页大小
	 * @param records 当前页数据列表
	 * @return 分页响应体
	 */
	public static <T> PageResult<T> of(long total, long current, long size, List<T> records) {
		PageResult<T> result = new PageResult<>();
		result.setTotal(total);
		result.setCurrent(current);
		result.setSize(size);
		result.setPages(size == 0 ? 0 : (total + size - 1) / size);
		result.setRecords(records);
		return result;
	}

	public long getTotal() {
		return total;
	}

	public void setTotal(long total) {
		this.total = total;
	}

	public long getPages() {
		return pages;
	}

	public void setPages(long pages) {
		this.pages = pages;
	}

	public long getCurrent() {
		return current;
	}

	public void setCurrent(long current) {
		this.current = current;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public List<T> getRecords() {
		return records;
	}

	public void setRecords(List<T> records) {
		this.records = records;
	}

}
