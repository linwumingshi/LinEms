package com.sanduo.energy.security;

/**
 * JWT 相关常量：声明键名 + 网关透传请求头名。
 *
 * <p>网关验签后把用户身份写入下行请求头（x-user-*），业务服务通过
 * 这些头读取当前用户，不再信任客户端直接提交的身份。</p>
 */
public final class JwtConstants {

    private JwtConstants() {
    }

    /** 认证请求头 */
    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /** JWT 自定义声明键 */
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_ENTERPRISE_ID = "eid";
    public static final String CLAIM_REAL_NAME = "rname";
    /** 会话 ID（Redis login_tokens:{sid} 的键，B 方案会话型令牌） */
    public static final String CLAIM_SESSION = "sid";

    /** 网关验签后注入下游的身份头（HTTP 头大小写不敏感） */
    public static final String HEADER_USER_ID = "x-user-id";
    public static final String HEADER_USER_NAME = "x-user-name";
    public static final String HEADER_TENANT_ID = "x-tenant-id";
    public static final String HEADER_ENTERPRISE_ID = "x-enterprise-id";
}
