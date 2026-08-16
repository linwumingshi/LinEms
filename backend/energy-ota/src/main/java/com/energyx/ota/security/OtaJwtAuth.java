package com.energyx.ota.security;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.security.JwtClaims;
import com.energyx.security.JwtConstants;
import com.energyx.security.JwtProperties;
import com.energyx.security.JwtTokenException;
import com.energyx.security.JwtTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OTA 下载接口「管理端登录态」鉴权。
 *
 * <p>
 * 升级包文件下载区分两类调用方：
 * <ul>
 * <li>设备 / 下发信封：携带 {@code expires+sign} 签名 URL（S5-4），无需登录，按签名校验防篡改；</li>
 * <li>管理端浏览器：仅持登录态 JWT，无签名参数——本类用与网关同源的 {@link JwtTokenUtil} 校验 Bearer，通过即放行。</li>
 * </ul>
 * 未携带或无效/过期 token 一律抛 {@link ErrorCode#UNAUTHORIZED}，由控制器转为 401。
 * </p>
 */
@Slf4j
@Component
public class OtaJwtAuth {

	private final JwtProperties jwtProperties;

	public OtaJwtAuth(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
	}

	/**
	 * 校验管理端下载请求的登录态，返回当前租户 ID。
	 * @param request 下载 HTTP 请求（含 Authorization: Bearer）
	 * @return JWT 中的租户 ID
	 * @throws BusinessException UNAUTHORIZED 缺少/无效/过期凭证
	 */
	public Long requireAuthenticatedTenant(HttpServletRequest request) {
		String auth = request.getHeader(JwtConstants.AUTH_HEADER);
		if (auth == null || !auth.startsWith(JwtConstants.BEARER_PREFIX)
				|| auth.length() <= JwtConstants.BEARER_PREFIX.length()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "下载需登录：缺少 Authorization 凭证");
		}
		String token = auth.substring(JwtConstants.BEARER_PREFIX.length()).trim();
		try {
			JwtClaims claims = JwtTokenUtil.parse(jwtProperties, token);
			return claims.tenantId();
		}
		catch (JwtTokenException | IllegalArgumentException e) {
			// IllegalArgumentException：secret 未配置等 fail-fast，统一视为未认证
			log.warn("[OTA] 下载登录态校验失败 reason={}", e.getMessage());
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "下载需登录：" + e.getMessage());
		}
	}

}
