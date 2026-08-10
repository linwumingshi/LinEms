package com.energyx.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 核心单元测试：签名/验签往返、过期、篡改、错密钥、错 issuer、缺声明、密钥长度。
 */
class JwtTokenUtilTest {

	private static final String SECRET = "test-secret-key-0123456789abcdefghijklmnopqrstuv";

	private static final String ISSUER = "energyx-ems";

	private JwtProperties props;

	@BeforeEach
	void setUp() {
		props = new JwtProperties();
		props.setSecret(SECRET);
		props.setExpireSeconds(7200);
		props.setIssuer(ISSUER);
	}

	private JwtClaims sampleClaims() {
		return new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", "sess-1");
	}

	@Test
	void signThenParse_returnsAllClaims() {
		String token = JwtTokenUtil.sign(props, sampleClaims());
		JwtClaims parsed = JwtTokenUtil.parse(props, token);
		assertEquals(1L, parsed.userId());
		assertEquals("admin", parsed.username());
		assertEquals(1L, parsed.tenantId());
		assertEquals(1L, parsed.enterpriseId());
		assertEquals("系统管理员", parsed.realName());
		assertEquals("sess-1", parsed.sessionId());
	}

	@Test
	void signThenParse_nullSessionId() {
		String token = JwtTokenUtil.sign(props, new JwtClaims(1L, "admin", 1L, 1L, "系统管理员", null));
		JwtClaims parsed = JwtTokenUtil.parse(props, token);
		assertEquals(null, parsed.sessionId());
	}

	@Test
	void signThenParse_nullEnterpriseAndRealName() {
		String token = JwtTokenUtil.sign(props, new JwtClaims(2L, "ops", 1L, null, null, null));
		JwtClaims parsed = JwtTokenUtil.parse(props, token);
		assertEquals(2L, parsed.userId());
		assertEquals("ops", parsed.username());
		assertEquals(null, parsed.enterpriseId());
		assertEquals(null, parsed.realName());
	}

	@Test
	void expiredToken_throwsExpired() {
		JwtProperties expired = new JwtProperties();
		expired.setSecret(SECRET);
		expired.setIssuer(ISSUER);
		expired.setExpireSeconds(-1); // 立即过期
		String token = JwtTokenUtil.sign(expired, sampleClaims());

		JwtTokenException ex = assertThrows(JwtTokenException.class, () -> JwtTokenUtil.parse(props, token));
		assertEquals(JwtTokenException.Reason.EXPIRED, ex.getReason());
	}

	@Test
	void tamperedToken_throwsInvalid() {
		String token = JwtTokenUtil.sign(props, sampleClaims());
		// 篡改签名区最后一个字符（signature 是 base64url，改动即验签失败）
		String tampered = token.substring(0, token.length() - 2) + "xx";

		JwtTokenException ex = assertThrows(JwtTokenException.class, () -> JwtTokenUtil.parse(props, tampered));
		assertEquals(JwtTokenException.Reason.INVALID, ex.getReason());
	}

	@Test
	void wrongSecret_throwsInvalid() {
		JwtProperties other = new JwtProperties();
		other.setSecret("another-secret-key-0123456789abcdefghijklmnopqr");
		other.setExpireSeconds(7200);
		other.setIssuer(ISSUER);
		String token = JwtTokenUtil.sign(other, sampleClaims());

		JwtTokenException ex = assertThrows(JwtTokenException.class, () -> JwtTokenUtil.parse(props, token));
		assertEquals(JwtTokenException.Reason.INVALID, ex.getReason());
	}

	@Test
	void wrongIssuer_throwsInvalid() {
		JwtProperties otherIssuer = new JwtProperties();
		otherIssuer.setSecret(SECRET);
		otherIssuer.setExpireSeconds(7200);
		otherIssuer.setIssuer("other-issuer");
		String token = JwtTokenUtil.sign(otherIssuer, sampleClaims());

		JwtTokenException ex = assertThrows(JwtTokenException.class, () -> JwtTokenUtil.parse(props, token));
		assertEquals(JwtTokenException.Reason.INVALID, ex.getReason());
	}

	@Test
	void missingRequiredClaims_throwsInvalid() {
		// 直接构造一个无 uid/tid 声明的 token（仅 subject + 有效签名）
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		String token = Jwts.builder()
			.issuer(ISSUER)
			.subject("admin")
			.issuedAt(new Date())
			.expiration(new Date(System.currentTimeMillis() + 60000))
			.signWith(key)
			.compact();

		JwtTokenException ex = assertThrows(JwtTokenException.class, () -> JwtTokenUtil.parse(props, token));
		assertEquals(JwtTokenException.Reason.INVALID, ex.getReason());
	}

	@Test
	void shortSecret_rejected() {
		JwtProperties bad = new JwtProperties();
		bad.setSecret("too-short");
		bad.setExpireSeconds(7200);
		bad.setIssuer(ISSUER);
		assertThrows(IllegalArgumentException.class, () -> JwtTokenUtil.sign(bad, sampleClaims()));
	}

	@Test
	void nullSecret_rejected() {
		JwtProperties bad = new JwtProperties();
		bad.setSecret(null);
		assertThrows(IllegalArgumentException.class, () -> JwtTokenUtil.sign(bad, sampleClaims()));
	}

}
