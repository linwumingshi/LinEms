package com.energyx.alarm.config;

import com.energyx.security.JwtProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * alarm 侧 JWT 配置绑定（WS 握手鉴权，P0-2）。 与网关/energy-system 的 energyx.jwt.* 保持一致（HS 系列共享密钥 +
 * issuer）。
 */
@Configuration
public class JwtConfig {

	@Bean
	@ConfigurationProperties(prefix = "energyx.jwt")
	public JwtProperties jwtProperties() {
		return new JwtProperties();
	}

}
