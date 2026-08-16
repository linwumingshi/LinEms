package com.energyx.ota.config;

import com.energyx.security.JwtProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OTA 下载接口登录态校验所需的 JWT 配置绑定。
 *
 * <p>
 * 与网关 {@code GlobalAuthFilter} 共用同一 {@code energyx.jwt.*}（HS 系列共享密钥 + issuer），
 * 保证签名与验签严格一致——管理端浏览器下载走登录态 JWT，设备下载走签名 URL，二者互不干扰。
 * </p>
 */
@Configuration
public class OtaJwtConfig {

	/**
	 * 绑定 energyx.jwt.secret/issuer/expireSeconds（Nacos 共享配置 energy-shared.yaml 注入）。
	 * @return JWT 配置 POJO
	 */
	@Bean
	@ConfigurationProperties(prefix = "energyx.jwt")
	public JwtProperties otaJwtProperties() {
		return new JwtProperties();
	}

}
