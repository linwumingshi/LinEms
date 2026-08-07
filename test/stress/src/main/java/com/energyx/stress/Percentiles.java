package com.energyx.stress;

import java.util.Arrays;

/**
 * 自包含分位数统计（无第三方依赖）：容量固定、线性插值百分位。
 *
 * <p>连接压测 / 控制链路压测的样本数已知（连接数 / 指令数），一次性分配容量；
 * 吞吐压测按秒采样（容量 = 秒数）。线程安全：写入加锁，统计取副本排序。</p>
 */
public final class Percentiles {

    private final long[] buf;
    private int size;

    public Percentiles(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 ≥ 1");
        }
        this.buf = new long[capacity];
    }

    public synchronized void add(long value) {
        if (size < buf.length) {
            buf[size++] = value;
        }
    }

    public synchronized int count() {
        return size;
    }

    public synchronized void reset() {
        size = 0;
    }

    public synchronized long min() {
        if (size == 0) {
            return 0;
        }
        long m = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            m = Math.min(m, buf[i]);
        }
        return m;
    }

    public synchronized long max() {
        if (size == 0) {
            return 0;
        }
        long m = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            m = Math.max(m, buf[i]);
        }
        return m;
    }

    public synchronized double mean() {
        if (size == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += buf[i];
        }
        return (double) sum / size;
    }

    /** 百分位（0~100），最近秩 + 线性插值；空样本返回 0。 */
    public synchronized long percentile(double p) {
        if (size == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(buf, size);
        Arrays.sort(sorted);
        double pos = p / 100.0 * (size - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = pos - lo;
        return Math.round(sorted[lo] + (sorted[hi] - sorted[lo]) * frac);
    }
}
