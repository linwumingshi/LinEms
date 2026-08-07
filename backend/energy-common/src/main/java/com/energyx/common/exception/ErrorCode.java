package com.energyx.common.exception;

/**
 * 业务错误码。
 * 分段约定：0 成功；4xxxx 客户端错误；5xxxx 系统错误；1xxxx 设备/影子/命令域；2xxxx 策略/告警域。
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 客户端 4xxxx ----
    BAD_REQUEST(40000, "请求参数错误"),
    PARAM_MISSING(40001, "缺少必填参数"),
    PARAM_INVALID(40002, "参数不合法"),
    UNAUTHORIZED(40100, "未认证或认证已过期"),
    TOKEN_EXPIRED(40101, "登录已过期，请重新登录"),
    FORBIDDEN(40300, "无权限访问"),
    NOT_FOUND(40400, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),
    CONFLICT(40900, "资源冲突"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试"),

    // ---- 设备域 1xxxx ----
    DEVICE_NOT_FOUND(10001, "设备不存在"),
    DEVICE_DUPLICATE(10002, "设备已存在"),
    DEVICE_OFFLINE(10003, "设备离线"),
    DEVICE_BANNED(10004, "设备已被封禁"),
    DEVICE_UNAUTHORIZED(10005, "设备认证失败"),
    PRODUCT_NOT_FOUND(10010, "产品不存在"),
    THING_MODEL_NOT_FOUND(10011, "物模型不存在"),
    THING_MODEL_VIOLATION(10012, "数据不符合物模型定义"),
    COMMAND_NOT_FOUND(10020, "指令不存在"),
    COMMAND_TIMEOUT(10021, "指令执行超时"),
    COMMAND_REJECTED(10022, "指令被设备拒绝"),
    COMMAND_REPEATED(10023, "指令重复提交"),
    SHADOW_VERSION_CONFLICT(10030, "影子版本冲突，请重试"),

    // ---- 策略/告警域 2xxxx ----
    STRATEGY_VIOLATION(20001, "操作违反安全约束"),
    STRATEGY_CONFLICT(20002, "策略冲突"),
    ALARM_RULE_NOT_FOUND(20010, "告警规则不存在"),

    // ---- 系统 5xxxx ----
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),
    DB_ERROR(50001, "数据访问异常"),
    CACHE_ERROR(50002, "缓存访问异常"),
    MQ_ERROR(50003, "消息队列异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
