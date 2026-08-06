package com.sanduo.energy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;

/**
 * JWT 签名 / 验签工具（HS 系列，jjwt 0.12）。
 *
 * <p>纯静态、无 Spring 依赖：网关（WebFlux）与业务服务（Servlet）共用，保证
 * 同一 secret/issuer 下签名与验签严格一致。算法由密钥长度决定
 * （{@code Keys.hmacShaKeyFor} 自动选型）：≥256bit → HS256，≥384bit → HS384，
 * ≥512bit → HS512；dev secret 48 字节=384bit，实际为 HS384。</p>
 */
public final class JwtTokenUtil {

    private static final int MIN_SECRET_BYTES = 32;

    private JwtTokenUtil() {
    }

    /**
     * 签发 token。
     *
     * @param props  JWT 配置（secret / expireSeconds / issuer）
     * @param claims 用户身份
     * @return JWT 字符串
     */
    public static String sign(JwtProperties props, JwtClaims claims) {
        Objects.requireNonNull(props, "JwtProperties 不能为空");
        Objects.requireNonNull(claims, "JwtClaims 不能为空");
        SecretKey key = secretKey(props.getSecret());
        long now = System.currentTimeMillis();
        JwtBuilder builder = Jwts.builder()
                .issuer(props.getIssuer())
                .subject(claims.username())
                .claim(JwtConstants.CLAIM_USER_ID, claims.userId())
                .claim(JwtConstants.CLAIM_TENANT_ID, claims.tenantId())
                .claim(JwtConstants.CLAIM_ENTERPRISE_ID, claims.enterpriseId())
                .claim(JwtConstants.CLAIM_REAL_NAME, claims.realName());
        if (claims.sessionId() != null) {
            builder.claim(JwtConstants.CLAIM_SESSION, claims.sessionId());
        }
        return builder.issuedAt(new Date(now))
                .expiration(new Date(now + props.getExpireSeconds() * 1000L))
                .signWith(key)
                .compact();
    }

    /**
     * 验签并解析 token（签名 + 过期 + issuer 强校验）。
     *
     * @param props JWT 配置
     * @param token 待校验 token
     * @return 用户身份
     * @throws JwtTokenException EXPIRED 已过期 / INVALID 无效（含缺必需声明）
     */
    public static JwtClaims parse(JwtProperties props, String token) throws JwtTokenException {
        Objects.requireNonNull(props, "JwtProperties 不能为空");
        SecretKey key = secretKey(props.getSecret());
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = claims.get(JwtConstants.CLAIM_USER_ID, Long.class);
            Long tenantId = claims.get(JwtConstants.CLAIM_TENANT_ID, Long.class);
            if (userId == null || tenantId == null) {
                throw new JwtTokenException(JwtTokenException.Reason.INVALID, "Token 缺少必需声明 uid/tid");
            }
            Long enterpriseId = claims.get(JwtConstants.CLAIM_ENTERPRISE_ID, Long.class);
            String realName = claims.get(JwtConstants.CLAIM_REAL_NAME, String.class);
            String sessionId = claims.get(JwtConstants.CLAIM_SESSION, String.class);
            return new JwtClaims(userId, claims.getSubject(), tenantId, enterpriseId, realName, sessionId);
        } catch (ExpiredJwtException e) {
            throw new JwtTokenException(JwtTokenException.Reason.EXPIRED, "Token 已过期: " + e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtTokenException(JwtTokenException.Reason.INVALID, "Token 无效: " + e.getMessage());
        }
    }

    private static SecretKey secretKey(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("sanduo.jwt.secret 未配置");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "sanduo.jwt.secret 长度不足，HS 系列需 ≥ " + MIN_SECRET_BYTES + " 字节，当前 " + bytes.length);
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
