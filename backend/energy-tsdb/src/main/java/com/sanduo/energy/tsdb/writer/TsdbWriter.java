package com.sanduo.energy.tsdb.writer;

/**
 * TDengine 写入端口：批量缓冲只依赖本接口，便于测试打桩与未来接入连接池。
 */
@FunctionalInterface
public interface TsdbWriter {

    /** 执行一条（含多 INSERT 块）写入语句；失败上抛由调用方决定重试/DLQ。 */
    void execute(String sql) throws Exception;
}
