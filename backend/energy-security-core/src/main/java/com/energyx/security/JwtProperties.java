package com.energyx.security;

/**
 * JWT 配置项（与 Spring 解耦的纯 POJO）。
 *
 * <p>
 * 由各服务通过 {@code @ConfigurationProperties(prefix = "energyx.jwt")} 绑定， 网关与业务服务必须配置一致（HS
 * 系列共享密钥，算法由密钥长度决定）。生产环境 secret 须经环境变量注入（P0-4 密钥外置），禁止使用默认值上线。
 * </p>
 */
public class JwtProperties {

	/**
	 * HS 系列共享密钥（≥32 字节，384bit 自动 HS384）。 P0-4 起无默认值：secret 必须由 Nacos
	 * 配置（energy-shared.yaml）或环境注入， 缺失时签发/验签即抛错（fail-fast），仓库内不落明文。
	 */
	private String secret;

	/** 令牌有效期（秒），默认 2 小时 */
	private int expireSeconds = 7200;

	/** 签发方（iss），验签时强校验 */
	private String issuer = "energyx-ems";

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public int getExpireSeconds() {
		return expireSeconds;
	}

	public void setExpireSeconds(int expireSeconds) {
		this.expireSeconds = expireSeconds;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

}
