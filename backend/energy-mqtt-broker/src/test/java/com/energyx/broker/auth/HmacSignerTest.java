package com.energyx.broker.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HMAC-SHA256 签名器单元测试（确定性 + 常数时间比较）。
 */
class HmacSignerTest {

	@Test
	void sign_isDeterministic() {
		String s1 = HmacSigner.sign("secret123", "pk1_dn1", "1750000000000", "abc123");
		String s2 = HmacSigner.sign("secret123", "pk1_dn1", "1750000000000", "abc123");
		assertEquals(s1, s2);
		assertEquals(64, s1.length()); // SHA-256 hex = 64 字符
	}

	@Test
	void sign_differsOnAnyInput() {
		String base = HmacSigner.sign("secret", "pk_dn", "1750000000000", "nonce1");
		assertNotEquals(base, HmacSigner.sign("secret", "pk_dn", "1750000000000", "nonce2"));
		assertNotEquals(base, HmacSigner.sign("secret", "pk_dn", "1750000000001", "nonce1"));
		assertNotEquals(base, HmacSigner.sign("secret2", "pk_dn", "1750000000000", "nonce1"));
	}

	@Test
	void constantTimeEquals_handlesNull() {
		assertFalse(HmacSigner.constantTimeEquals(null, "x"));
		assertFalse(HmacSigner.constantTimeEquals("x", null));
	}

	@Test
	void constantTimeEquals_works() {
		assertTrue(HmacSigner.constantTimeEquals("abc", "abc"));
		assertFalse(HmacSigner.constantTimeEquals("abc", "abd"));
		assertFalse(HmacSigner.constantTimeEquals("abc", "abcd"));
	}

}
