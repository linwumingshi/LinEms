package com.energyx.system.config;

import com.energyx.system.security.filter.JwtAuthenticationTokenFilter;
import com.energyx.system.security.handle.JsonAccessDeniedHandler;
import com.energyx.system.security.handle.JsonAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（B 方案，对齐若依 SecurityConfig）。
 *
 * <p>
 * STATELESS + JWT 过滤器：仅登录/验证码匿名，其余全部需认证； 方法级鉴权由 {@code @EnableMethodSecurity} +
 * {@code @ss.hasPermi} 控制。
 * </p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private final JsonAuthenticationEntryPoint authenticationEntryPoint;

	private final JsonAccessDeniedHandler accessDeniedHandler;

	private final JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;

	public SecurityConfig(JsonAuthenticationEntryPoint authenticationEntryPoint,
			JsonAccessDeniedHandler accessDeniedHandler, JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter) {
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
		this.jwtAuthenticationTokenFilter = jwtAuthenticationTokenFilter;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
			// 无状态 JWT 鉴权，禁用 CSRF
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.authorizeHttpRequests(auth -> auth.requestMatchers("/system/auth/login", "/system/auth/captcha")
				.permitAll()
				.requestMatchers("/actuator/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			// JWT 会话过滤器先于用户名密码过滤器
			.addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	/**
	 * 认证管理器：由 AuthenticationConfiguration 装配 DaoAuthenticationProvider （自动绑定
	 * UserDetailsService + PasswordEncoder Bean）。
	 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

}
