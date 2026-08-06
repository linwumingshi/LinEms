package com.sanduo.energy.security;

/**
 * JWT 校验异常：区分过期与无效，供网关返回不同业务码。
 */
public class JwtTokenException extends RuntimeException {

    /** 失败原因：EXPIRED 已过期；INVALID 签名/结构/声明缺失 */
    public enum Reason {
        EXPIRED,
        INVALID
    }

    private final Reason reason;

    public JwtTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
